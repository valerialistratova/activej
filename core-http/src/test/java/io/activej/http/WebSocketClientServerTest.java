package io.activej.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufQueue;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelSupplier;
import io.activej.eventloop.Eventloop;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static io.activej.bytebuf.ByteBufStrings.wrapUtf8;
import static io.activej.http.TestUtils.chunker;
import static io.activej.http.WebSocketConstants.HANDSHAKE_FAILED;
import static io.activej.https.SslUtils.createTestSslContext;
import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static io.activej.test.TestUtils.getFreePort;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.*;

public final class WebSocketClientServerTest {
	private static final int PORT = getFreePort();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	@Test
	public void testEcho() throws IOException {
		startTestServer(request -> HttpResponse.ok200().withBodyStream(request.getBodyStream()));

		String inputData = IntStream.range(0, 100).mapToObj(String::valueOf).collect(joining());

		ByteBuf result = await(AsyncHttpClient.create(Eventloop.getCurrentEventloop())
				.request(HttpRequest.webSocket("ws://127.0.0.1:" + PORT)
						.withBodyStream(ChannelSupplier.of(wrapUtf8(inputData))
								.transformWith(chunker())
								.mapAsync(item -> Promises.delay(1L, item))))
				.async()
				.then(response -> response.getBodyStream().toCollector(ByteBufQueue.collector())));

		assertEquals(inputData, result.asString(UTF_8));
	}

	@Test
	public void testServerWithoutStream() throws IOException {
		Ref<ByteBuf> byteBufRef = new Ref<>();
		startTestServer(request -> {
			request.getBodyStream().toCollector(ByteBufQueue.collector())
					.whenResult(byteBufRef::set);
			return HttpResponse.ok200();
		});

		await(AsyncHttpClient.create(Eventloop.getCurrentEventloop())
				.request(HttpRequest.webSocket("ws://127.0.0.1:" + PORT)
						.withBodyStream(ChannelSupplier.of(wrapUtf8("Hello"))))
				.then(response -> response.getBodyStream().streamTo(ChannelConsumer.ofConsumer($ -> fail()))));

		assertEquals("Hello", byteBufRef.get().asString(UTF_8));
	}

	@Test
	public void testClientWithoutStream() throws IOException {
		startTestServer(request -> {
			request.getBodyStream().streamTo(ChannelConsumer.ofConsumer($ -> fail()));
			return HttpResponse.ok200()
					.withBodyStream(ChannelSupplier.of(wrapUtf8("Hello")));
		});

		ByteBuf result = await(AsyncHttpClient.create(Eventloop.getCurrentEventloop())
				.request(HttpRequest.webSocket("ws://127.0.0.1:" + PORT))
				.then(response -> response.getBodyStream().toCollector(ByteBufQueue.collector())));

		assertEquals("Hello", result.asString(UTF_8));
	}

	@Test
	public void testServerWSException() throws IOException {
		RefInt counter = new RefInt(100);
		ByteBufQueue queue = new ByteBufQueue();
		String reason = "Some error";
		WebSocketException exception = new WebSocketException(getClass(), 4321, reason);

		startTestServer(request -> HttpResponse.ok200()
				.withBodyStream(ChannelSupplier.of(() -> Promise.of(wrapUtf8("hello")))
						.mapAsync(buf -> {
							if (counter.dec() < 0) {
								buf.recycle();
								return Promise.ofException(exception);
							} else {
								return Promise.of(buf);
							}
						})));

		Throwable receivedEx = awaitException(AsyncHttpClient.create(Eventloop.getCurrentEventloop())
				.request(HttpRequest.webSocket("ws://127.0.0.1:" + PORT))
				.then(response -> response.getBodyStream().streamTo(ChannelConsumer.ofConsumer(queue::add))));

		assertThat(receivedEx, instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(4321), ((WebSocketException) receivedEx).getCode());
		assertEquals(reason, ((WebSocketException) receivedEx).getReason());

		String[] split = queue.takeRemaining().asString(UTF_8).split("(?<=\\G.{5})");
		assertEquals(100, split.length);
		for (String s : split) {
			assertEquals("hello", s);
		}
	}

	@Test
	public void testSecureWebSocketsCloseByClient() throws IOException {
		String inputData = IntStream.range(0, 100).mapToObj(String::valueOf).collect(joining());
		ExecutorService executor = Executors.newSingleThreadExecutor();
		ByteBufQueue result = new ByteBufQueue();

		startSecureTestServer(request -> {
			request.getBodyStream().streamTo(ChannelConsumer.ofConsumer(result::add));
			return HttpResponse.ok200();
		});

		await(AsyncHttpClient.create(Eventloop.getCurrentEventloop())
				.withSslEnabled(createTestSslContext(), executor)
				.request(HttpRequest.webSocket("wss://127.0.0.1:" + PORT)
						.withBodyStream(ChannelSupplier.of(wrapUtf8(inputData))
								.transformWith(chunker())
								.mapAsync(item -> Promises.delay(1L, item))))
				.then(response -> response.getBodyStream().streamTo(ChannelConsumer.ofConsumer($ -> fail()))));

		assertEquals(inputData, result.takeRemaining().asString(UTF_8));
		executor.shutdown();
	}

	@Test
	public void testSecureWebSocketsCloseByServer() throws IOException {
		String inputData = IntStream.range(0, 100).mapToObj(String::valueOf).collect(joining());
		ExecutorService executor = Executors.newSingleThreadExecutor();
		ByteBufQueue result = new ByteBufQueue();

		startSecureTestServer(request -> HttpResponse.ok200()
				.withBodyStream(ChannelSupplier.of(wrapUtf8(inputData))
						.transformWith(chunker())
						.mapAsync(item -> Promises.delay(1L, item))));

		await(AsyncHttpClient.create(Eventloop.getCurrentEventloop())
				.withSslEnabled(createTestSslContext(), executor)
				.request(HttpRequest.webSocket("wss://127.0.0.1:" + PORT))
				.then(response -> response.getBodyStream().streamTo(ChannelConsumer.ofConsumer(result::add))));

		assertEquals(inputData, result.takeRemaining().asString(UTF_8));
		executor.shutdown();
	}

	@Test
	public void testRejectedHandshake() throws IOException {
		startTestServer(request -> HttpResponse.ofCode(403));
		Throwable exception = awaitException(AsyncHttpClient.create(Eventloop.getCurrentEventloop())
				.request(HttpRequest.webSocket("ws://127.0.0.1:" + PORT)
						.withBodyStream(ChannelSupplier.of(wrapUtf8("Test")))));

		assertEquals(HANDSHAKE_FAILED, exception);
	}

	private void startTestServer(AsyncServlet servlet) throws IOException {
		AsyncHttpServer.create(Eventloop.getCurrentEventloop(), RoutingServlet.create()
				.mapWebSocket("/", servlet))
				.withListenPort(PORT)
				.withAcceptOnce()
				.listen();
	}

	private void startSecureTestServer(AsyncServlet servlet) throws IOException {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		AsyncHttpServer server = AsyncHttpServer.create(Eventloop.getCurrentEventloop(), RoutingServlet.create()
				.mapWebSocket("/", servlet))
				.withSslListenPort(createTestSslContext(), executor, PORT)
				.withAcceptOnce();
		server.getCloseNotification().whenComplete(executor::shutdown);
		server.listen();
	}

}
