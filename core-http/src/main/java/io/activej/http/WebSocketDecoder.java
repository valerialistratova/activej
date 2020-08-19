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
import io.activej.bytebuf.ByteBufQueue;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelOutput;
import io.activej.csp.binary.BinaryChannelInput;
import io.activej.csp.binary.BinaryChannelSupplier;
import io.activej.csp.binary.ByteBufsDecoder;
import io.activej.csp.dsl.WithBinaryChannelInput;
import io.activej.csp.dsl.WithChannelTransformer;
import io.activej.csp.process.AbstractCommunicatingProcess;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;

import static io.activej.common.Checks.checkState;
import static io.activej.csp.binary.ByteBufsDecoder.ofFixedSize;
import static io.activej.http.WebSocketConstants.*;
import static io.activej.http.WebSocketConstants.OpCode.*;
import static java.nio.charset.StandardCharsets.UTF_8;

final class WebSocketDecoder extends AbstractCommunicatingProcess
		implements WithChannelTransformer<WebSocketDecoder, ByteBuf, ByteBuf>, WithBinaryChannelInput<WebSocketDecoder> {

	private static final byte FIRST_BIT_MASK = (byte) 0b10000000;
	private static final byte OP_CODE_MASK = 0b00001111;
	private static final byte LAST_7_BITS_MASK = 0b01111111;

	private static final ByteBufsDecoder<Byte> SINGLE_BYTE_DECODER = queue -> queue.hasRemainingBytes(1) ? queue.getByte() : null;

	private final PingPongHandler pingPongHandler;
	private final byte[] mask = new byte[4];
	private final boolean masked;
	private final SettablePromise<WebSocketException> closeReceivedPromise = new SettablePromise<>();

	private ByteBufQueue bufs;
	private BinaryChannelSupplier input;
	private ChannelConsumer<ByteBuf> output;

	private int maskIndex;
	private WebSocketConstants.OpCode currentOpCode;

	private final ByteBufQueue controlMessageQueue = new ByteBufQueue();

	// region creators
	private WebSocketDecoder(PingPongHandler pingPongHandler, boolean masked) {
		this.pingPongHandler = pingPongHandler;
		this.masked = masked;
	}

	public static WebSocketDecoder create(PingPongHandler pingPongHandler, boolean maskRequired) {
		return new WebSocketDecoder(pingPongHandler, maskRequired);
	}

	@Override
	public BinaryChannelInput getInput() {
		return input -> {
			checkState(this.input == null, "Input already set");
			this.input = sanitize(input);
			this.bufs = input.getBufs();
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
	// endregion

	@Override
	protected void beforeProcess() {
		checkState(input != null, "Input was not set");
		checkState(output != null, "Output was not set");
	}

	public Promise<WebSocketException> getCloseReceivedPromise() {
		return closeReceivedPromise;
	}

	@Override
	protected void doProcess() {
		processOpCode();
	}

	private void processOpCode() {
		input.parse(SINGLE_BYTE_DECODER)
				.whenResult(firstByte -> {
					byte opCodeByte = (byte) (firstByte & OP_CODE_MASK);
					currentOpCode = fromOpCodeByte(opCodeByte);

					if (currentOpCode == null) {
						closeEx(UNKNOWN_OP_CODE);
						return;
					}

					processLength();
				});
	}

	private void processLength() {
		assert currentOpCode != null;

		input.parse(SINGLE_BYTE_DECODER)
				.whenResult(maskAndLen -> {
					boolean msgMasked = (maskAndLen & FIRST_BIT_MASK) != 0;
					if (this.masked && !msgMasked) {
						closeEx(MASK_REQUIRED);
					}
					if (!this.masked && msgMasked) {
						closeEx(MASK_SHOULD_NOT_BE_PRESENT);
					}
					maskIndex = msgMasked ? 0 : -1;
					byte length = (byte) (maskAndLen & LAST_7_BITS_MASK);
					if (currentOpCode.isControlCode() && length > 125) {
						closeEx(INVALID_PAYLOAD_LENGTH);
						return;
					}
					if (length == 126) {
						processLengthEx(2);
					} else if (length == 127) {
						processLengthEx(8);
					} else {
						processMask(length);
					}
				});
	}

	private void processLengthEx(int numberOfBytes) {
		assert numberOfBytes == 2 || numberOfBytes == 8;
		input.parse(ofFixedSize(numberOfBytes))
				.whenResult(lenBuf -> {
					long len;
					if (numberOfBytes == 2) {
						len = Short.toUnsignedLong(lenBuf.readShort());
					} else {
						len = lenBuf.readLong();
						if (len < 0) {
							closeEx(INVALID_PAYLOAD_LENGTH);
						}
					}
					lenBuf.recycle();
					processMask(len);
				});
	}

	private void processMask(long length) {
		if (maskIndex == -1) {
			processPayload(length);
		} else {
			input.parse(queue -> !queue.hasRemainingBytes(4) ? null : queue)
					.whenResult(bufs -> {
						bufs.drainTo(mask, 0, 4);
						processPayload(length);
					});
		}
	}

	private void processPayload(long length) {
		assert length >= 0;
		ByteBuf buf = bufs.takeAtMost(length > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) length);
		unmask(buf);
		long newLength = length - buf.readRemaining();
		if (newLength != 0) {
			Promise.complete()
					.then(() -> {
						if (buf.canRead()) {
							if (currentOpCode.isControlCode()) {
								controlMessageQueue.add(buf);
								return Promise.complete();
							}
							return output.accept(buf);
						}
						return Promise.complete();
					})
					.then(() -> bufs.isEmpty() ? input.needMoreData() : Promise.complete())
					.whenResult(() -> processPayload(newLength));
			return;
		}

		if (currentOpCode.isControlCode()) {
			controlMessageQueue.add(buf);
			processControlPayload();
		} else {
			output.accept(buf)
					.whenResult(this::processOpCode);
		}
	}

	private void processControlPayload() {
		ByteBuf controlPayload = controlMessageQueue.takeRemaining();
		if (currentOpCode == OP_CLOSE) {
			if (controlPayload.canRead()) {
				int payloadLength = controlPayload.readRemaining();
				if (payloadLength < 2 || payloadLength > 125) {
					onCloseReceived(INVALID_PAYLOAD_LENGTH);
					closeEx(INVALID_PAYLOAD_LENGTH);
					return;
				}
				int statusCode = Short.toUnsignedInt(controlPayload.readShort());
				if (statusCode == 1000) {
					controlPayload.recycle();
					output.acceptEndOfStream()
							.whenComplete(() -> onCloseReceived(REGULAR_CLOSE))
							.whenResult(this::completeProcess);
				} else {
					WebSocketException exception = new WebSocketException(WebSocketDecoder.class, statusCode, controlPayload.asString(UTF_8));
					onCloseReceived(exception);
					closeEx(exception);
				}
			} else {
				controlPayload.recycle();
				onCloseReceived(STATUS_CODE_MISSING);
				closeEx(STATUS_CODE_MISSING);
			}
		} else if (currentOpCode == OP_PING) {
			pingPongHandler.onPing(controlPayload);
			processOpCode();
		} else {
			assert currentOpCode == OP_PONG;
			pingPongHandler.onPong(controlPayload);
			processOpCode();
		}
	}

	private void unmask(ByteBuf buf) {
		if (maskIndex == -1 || !buf.canRead()) {
			return;
		}
		for (int head = buf.head(); head < buf.tail(); head++) {
			buf.set(head, (byte) (buf.at(head) ^ mask[maskIndex++ % 4]));
		}
	}

	private void onCloseReceived(WebSocketException e) {
		closeReceivedPromise.trySet(e);
	}

	@Override
	protected void doClose(Throwable e) {
		if (output != null) {
			output.closeEx(e);
		}
	}

}
