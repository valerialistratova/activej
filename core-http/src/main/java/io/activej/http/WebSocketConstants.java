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

import io.activej.common.exception.StacklessException;
import org.jetbrains.annotations.Nullable;

public final class WebSocketConstants {
	// region exceptions
	public static final WebSocketException REGULAR_CLOSE = new WebSocketException(WebSocketConstants.class, 1000, "Regular close");
	public static final WebSocketException CLOSE_EXCEPTION = new WebSocketException(WebSocketConstants.class, 1001, "Closed");
	public static final WebSocketException UNKNOWN_OP_CODE = new WebSocketException(WebSocketConstants.class, 1002, "Unknown op code");
	public static final WebSocketException INVALID_PAYLOAD_LENGTH = new WebSocketException(WebSocketConstants.class, 1002, "Status code missing");
	public static final WebSocketException MASK_REQUIRED = new WebSocketException(WebSocketConstants.class, 1002, "Message should be masked");
	public static final WebSocketException MASK_SHOULD_NOT_BE_PRESENT = new WebSocketException(WebSocketConstants.class, 1002, "Message should not be masked");
	public static final WebSocketException STATUS_CODE_MISSING = new WebSocketException(WebSocketConstants.class, 1005, "Status code missing");
	public static final WebSocketException CLOSE_FRAME_MISSING = new WebSocketException(WebSocketConstants.class, 1006, "Peer did not send CLOSE frame");

	public static final StacklessException HANDSHAKE_FAILED = new StacklessException(WebSocketConstants.class, "Server failed to perform a proper opening handshake");
	// endregion

	static final String MAGIC_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	enum OpCode {
		OP_CONTINUATION((byte) 0x0),
		OP_TEXT((byte) 0x1),
		OP_BINARY((byte) 0x2),
		OP_CLOSE((byte) 0x8),
		OP_PING((byte) 0x9),
		OP_PONG((byte) 0xA);

		private final byte code;

		public byte getCode() {
			return code;
		}

		OpCode(byte code) {
			this.code = code;
		}

		boolean isControlCode() {
			return (code >> 2) != 0;
		}

		boolean isMessageCode() {
			return !isControlCode();
		}

		@Nullable
		static WebSocketConstants.OpCode fromOpCodeByte(byte b) {
			for (OpCode value : OpCode.values()) {
				if (value.code == b) {
					return value;
				}
			}
			return null;
		}
	}
}
