/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.bytebuf.ByteBufStrings;
import io.activej.common.annotation.Beta;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelInput;
import io.activej.csp.ChannelOutput;
import io.activej.csp.ChannelSupplier;
import io.activej.csp.dsl.WithChannelTransformer;
import io.activej.csp.process.AbstractCommunicatingProcess;
import io.activej.http.WebSocketConstants.OpCode;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;

import static io.activej.common.Checks.checkState;
import static io.activej.http.WebSocketConstants.OpCode.*;
import static io.activej.http.WebSocketConstants.REGULAR_CLOSE;

@Beta
final class WebSocketEncoder extends AbstractCommunicatingProcess
		implements WithChannelTransformer<WebSocketEncoder, ByteBuf, ByteBuf>, PingPongHandler {
	private static final ThreadLocalRandom RANDOM = ThreadLocalRandom.current();

	private final boolean masked;
	private final SettablePromise<Void> closeSentPromise = new SettablePromise<>();

	private ChannelSupplier<ByteBuf> input;
	private ChannelConsumer<ByteBuf> output;

	@Nullable
	private Promise<Void> pendingPromise;
	private OpCode payloadOpCode = OP_BINARY;
	private boolean closing;

	// region creators
	private WebSocketEncoder(boolean masked) {
		this.masked = masked;
	}

	public static WebSocketEncoder create(boolean masked) {
		return new WebSocketEncoder(masked);
	}

	@SuppressWarnings("ConstantConditions") //check input for clarity
	@Override
	public ChannelInput<ByteBuf> getInput() {
		return input -> {
			checkState(this.input == null, "Input already set");
			this.input = sanitize(input);
			if (this.input != null && this.output != null) startProcess();
			return getProcessCompletion();
		};
	}

	@SuppressWarnings("ConstantConditions") //check output for clarity
	@Override
	public ChannelOutput<ByteBuf> getOutput() {
		return output -> {
			checkState(this.output == null, "Output already set");
			this.output = sanitize(output);
			if (this.input != null && this.output != null) startProcess();
		};
	}

	public void useTextEncoding(boolean use) {
		this.payloadOpCode = use ? OP_TEXT : OP_BINARY;
	}
	// endregion

	@Override
	protected void beforeProcess() {
		checkState(input != null, "Input was not set");
		checkState(output != null, "Output was not set");
	}

	@Override
	protected void doProcess() {
		input.filter(ByteBuf::canRead)
				.streamTo(ChannelConsumer.of(buf -> doAccept(encodeData(buf))))
				.then(() -> sendCloseFrame(REGULAR_CLOSE))
				.whenComplete(($, e) -> input.closeEx(e == null ? REGULAR_CLOSE : e))
				.whenResult(this::completeProcess);
	}

	private ByteBuf doEncode(ByteBuf buf, OpCode opCode) {
		int bufSize = buf.readRemaining();
		int lenSize = bufSize < 126 ? 1 : bufSize < 65536 ? 3 : 9;

		ByteBuf framedBuf = ByteBufPool.allocate(1 + lenSize + (masked ? 4 : 0) + bufSize);
		framedBuf.writeByte((byte) (opCode.getCode() | 0x80));
		if (lenSize == 1) {
			framedBuf.writeByte((byte) bufSize);
		} else if (lenSize == 3) {
			framedBuf.writeByte((byte) 126);
			framedBuf.writeShort((short) bufSize);
		} else {
			framedBuf.writeByte((byte) 127);
			framedBuf.writeLong(bufSize);
		}
		if (masked) {
			int idx = framedBuf.head() + 1;
			framedBuf.set(idx, (byte) (framedBuf.at(idx) | 0x80));
			byte[] mask = new byte[4];
			RANDOM.nextBytes(mask);
			framedBuf.put(mask);
			for (int i = 0, head = buf.head(); head < buf.tail(); head++) {
				buf.set(head, (byte) (buf.at(head) ^ mask[i++ % 4]));
			}
		}
		framedBuf.put(buf);
		buf.recycle();
		return framedBuf;
	}

	private ByteBuf encodeData(ByteBuf buf) {
		return doEncode(buf, payloadOpCode);
	}

	private ByteBuf encodePong(ByteBuf buf) {
		return doEncode(buf, OP_PONG);
	}

	private ByteBuf encodeClose(WebSocketException e) {
		Integer code = e.getCode();
		String reason = e.getReason();
		ByteBuf closePayload = ByteBufPool.allocate(code == null ? 0 : (2 + reason.length()));
		if (code != null) {
			closePayload.writeShort(code.shortValue());
		}
		if (!reason.isEmpty()) {
			ByteBuf reasonBuf = ByteBufStrings.wrapUtf8(reason);
			closePayload.put(reasonBuf);
			reasonBuf.recycle();
		}
		return doEncode(closePayload, OP_CLOSE);
	}

	@Override
	public void onPing(ByteBuf payload) {
		doAccept(encodePong(payload));
	}

	@Override
	public void onPong(ByteBuf payload) {
		payload.recycle();
	}

	public Promise<Void> getCloseSentPromise() {
		return closeSentPromise;
	}

	private Promise<Void> doAccept(@Nullable ByteBuf buf) {
		if (pendingPromise == null) {
			return pendingPromise = output.accept(buf);
		} else {
			Promise<Void> pendingPromise = this.pendingPromise;
			this.pendingPromise = null;
			return this.pendingPromise = pendingPromise.then(() -> output.accept(buf));
		}
	}

	private Promise<Void> sendCloseFrame(WebSocketException e) {
		if (closing) return Promise.complete();
		closing = true;
		return doAccept(encodeClose(e))
				.then(() -> doAccept(null))
				.whenComplete(() -> closeSentPromise.trySet(null));
	}

	@Override
	protected void doClose(Throwable e) {
		if (output == null || input == null) {
			return;
		}
		WebSocketException exception;
		if (e instanceof WebSocketException && ((WebSocketException) e).canBeEchoed()) {
			exception = (WebSocketException) e;
		} else {
			exception = WebSocketConstants.CLOSE_EXCEPTION;
		}
		sendCloseFrame(exception)
				.whenComplete(($, e1) -> input.closeEx(e1 == null ? e : e1));
	}

}
