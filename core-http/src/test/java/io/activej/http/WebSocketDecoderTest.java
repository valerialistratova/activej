package io.activej.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufQueue;
import io.activej.csp.ChannelSupplier;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.bytebuf.ByteBuf.wrapForReading;
import static io.activej.http.TestUtils.*;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

public final class WebSocketDecoderTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void decodeSingleFrameMaskedTextMessage() {
		// Masked "Hello" message, RFC 6455 - 5.7
		byte[] frame = new byte[]{(byte) 0x81, (byte) 0x85, (byte) 0x37, (byte) 0xfa, (byte) 0x21,
				(byte) 0x3d, (byte) 0x7f, (byte) 0x9f, (byte) 0x4d, (byte) 0x51, (byte) 0x58};

		ByteBuf result = await(ChannelSupplier.of(wrapForReading(frame), closeMasked())
				.transformWith(chunker())
				.transformWith(WebSocketDecoder.create(HANDLER_STUB, true))
				.toCollector(ByteBufQueue.collector()));

		assertEquals("Hello", result.asString(UTF_8));
	}

	@Test
	public void decodeSingleFrameUnmaskedTextMessage() {
		// Unmasked "Hello" message, RFC 6455 - 5.7
		byte[] frame = new byte[]{(byte) 0x81, (byte) 0x05, (byte) 0x48, (byte) 0x65, (byte) 0x6c, (byte) 0x6c, (byte) 0x6f};

		ByteBuf result = await(ChannelSupplier.of(wrapForReading(frame), closeUnmasked())
				.transformWith(chunker())
				.transformWith(WebSocketDecoder.create(HANDLER_STUB, false))
				.toCollector(ByteBufQueue.collector()));

		assertEquals("Hello", result.asString(UTF_8));
	}

	@Test
	public void decodeFragmentedUnmaskedTextMessage() {
		// Contains "Hel" message, RFC 6455 - 5.7
		byte[] frame1 = new byte[]{(byte) 0x01, (byte) 0x03, (byte) 0x48, (byte) 0x65, (byte) 0x6c};
		// Contains "lo" message, RFC 6455 - 5.7
		byte[] frame2 = new byte[]{(byte) 0x80, (byte) 0x02, (byte) 0x6c, (byte) 0x6f};

		ByteBuf result = await(ChannelSupplier.of(wrapForReading(frame1), wrapForReading(frame2), closeUnmasked())
				.transformWith(chunker())
				.transformWith(WebSocketDecoder.create(HANDLER_STUB, false))
				.toCollector(ByteBufQueue.collector()));

		assertEquals("Hello", result.asString(UTF_8));
	}

	@Test
	public void decode256BytesInSingleUnmaskedFrame() {
		// RFC 6455 - 5.7
		byte[] header = new byte[]{(byte) 0x82, (byte) 0x7E, (byte) 0x01, (byte) 0x00};
		byte[] payload = randomBytes(256);

		ByteBuf result = await(ChannelSupplier.of(wrapForReading(header), wrapForReading(payload), closeUnmasked())
				.transformWith(chunker())
				.transformWith(WebSocketDecoder.create(HANDLER_STUB, false))
				.toCollector(ByteBufQueue.collector()));

		assertArrayEquals(payload, result.asArray());
	}

	@Test
	public void decode64KiBInSingleUnmaskedFrame() {
		// RFC 6455 - 5.7
		byte[] header = new byte[]{(byte) 0x82, (byte) 0x7F, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
				(byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00};
		byte[] payload = randomBytes(65536);

		ByteBuf result = await(ChannelSupplier.of(wrapForReading(header), wrapForReading(payload), closeUnmasked())
				.transformWith(chunker())
				.transformWith(WebSocketDecoder.create(HANDLER_STUB, false))
				.toCollector(ByteBufQueue.collector()));

		assertArrayEquals(payload, result.asArray());
	}

	@Test
	public void decodeUnmaskedPing() {
		// As first frame should be either Binary or Text
		byte[] frame = new byte[]{(byte) 0x81, (byte) 0x05, (byte) 0x48, (byte) 0x65, (byte) 0x6c, (byte) 0x6c, (byte) 0x6f};
		// Contains a body of "Hello", RFC 6455 - 5.7
		byte[] pingFrame = new byte[]{(byte) 0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f};

		ByteBufQueue pingMessage = new ByteBufQueue();
		ByteBuf message = await(ChannelSupplier.of(wrapForReading(frame), wrapForReading(pingFrame), closeUnmasked())
				.transformWith(chunker())
				.transformWith(WebSocketDecoder.create(PingPongHandler.of(pingMessage::add, $ -> fail()), false))
				.toCollector(ByteBufQueue.collector()));

		assertEquals("Hello", pingMessage.takeRemaining().asString(UTF_8));
		assertEquals("Hello", message.asString(UTF_8));
	}

	@Test
	public void decodeMaskedPong() {
		// As first frame should be either Binary or Text
		byte[] frame = new byte[]{(byte) 0x81, (byte) 0x85, (byte) 0x37, (byte) 0xfa, (byte) 0x21,
				(byte) 0x3d, (byte) 0x7f, (byte) 0x9f, (byte) 0x4d, (byte) 0x51, (byte) 0x58};
		// Contains a body of "Hello", RFC 6455 - 5.7
		byte[] pingFrame = new byte[]{(byte) 0x8a, (byte) 0x85, (byte) 0x37, (byte) 0xfa, (byte) 0x21,
				(byte) 0x3d, (byte) 0x7f, (byte) 0x9f, (byte) 0x4d, (byte) 0x51, (byte) 0x58};

		ByteBufQueue pongMessage = new ByteBufQueue();
		ByteBuf message = await(ChannelSupplier.of(wrapForReading(frame), wrapForReading(pingFrame), closeMasked())
				.transformWith(chunker())
				.transformWith(WebSocketDecoder.create(PingPongHandler.of($ -> fail(), pongMessage::add), true))
				.toCollector(ByteBufQueue.collector()));

		assertEquals("Hello", pongMessage.takeRemaining().asString(UTF_8));
		assertEquals("Hello", message.asString(UTF_8));
	}
}
