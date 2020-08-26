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
import io.activej.common.exception.parse.ParseException;
import io.activej.common.exception.parse.UnknownFormatException;
import io.activej.csp.ChannelSupplier;
import io.activej.csp.ChannelSuppliers;
import io.activej.csp.queue.ChannelZeroBuffer;
import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncHttpClient.Inspector;
import io.activej.http.stream.BufsConsumerGzipInflater;
import io.activej.net.socket.tcp.AsyncTcpSocket;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

import static io.activej.bytebuf.ByteBufStrings.SP;
import static io.activej.bytebuf.ByteBufStrings.decodePositiveInt;
import static io.activej.csp.ChannelSuppliers.concat;
import static io.activej.http.HttpHeaders.*;
import static io.activej.http.HttpMessage.MUST_LOAD_BODY;
import static io.activej.http.HttpUtils.*;
import static io.activej.http.Protocol.WS;
import static io.activej.http.Protocol.WSS;
import static io.activej.http.WebSocketConstants.HANDSHAKE_FAILED;
import static io.activej.http.WebSocketConstants.REGULAR_CLOSE;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 * <p>
 * This class is responsible for sending and receiving HTTP requests.
 * It's made so that one instance of it corresponds to one networking socket.
 * That's why instances of those classes are all stored in one of three pools in their
 * respective {@link AsyncHttpClient} instance.
 * </p>
 * <p>
 * Those pools are: <code>poolKeepAlive</code>, <code>poolReading</code>, and <code>poolWriting</code>.
 * </p>
 * Path between those pools that any connection takes can be represented as a state machine,
 * described as a GraphViz graph below.
 * Nodes with (null) descriptor mean that the connection is not in any pool.
 * <pre>
 * digraph {
 *     label="Single HttpConnection state machine"
 *     rankdir="LR"
 *
 *     "open(null)"
 *     "closed(null)"
 *     "reading"
 *     "writing"
 *     "keep-alive"
 *     "taken(null)"
 *
 *     "writing" -> "closed(null)" [color="#ff8080", style=dashed, label="value reset/write timeout"]
 *     "reading" -> "closed(null)" [color="#ff8080", style=dashed, label="value reset/read timeout"]
 *     "keep-alive" -> "closed(null)" [color="#ff8080", style=dashed, label="value reset"]
 *     "taken(null)" -> "closed(null)" [color="#ff8080", style=dashed, label="value reset"]
 *
 *     "open(null)" -> "writing" [label="send request"]
 *     "writing" -> "reading"
 *     "reading" -> "closed(null)" [label="received response\n(no keep-alive)"]
 *     "reading" -> "keep-alive" [label="received response"]
 *     "keep-alive" -> "taken(null)" [label="reuse connection"]
 *     "taken(null)" -> "writing" [label="send request"]
 *     "keep-alive" -> "closed(null)" [label="expiration"]
 *
 *     { rank=same; "open(null)", "closed(null)" }
 *     { rank=same; "reading", "writing", "keep-alive" }
 * }
 * </pre>
 */
final class HttpClientConnection extends AbstractHttpConnection {
	public static final ParseException INVALID_RESPONSE = new UnknownFormatException(HttpClientConnection.class, "Invalid response");
	public static final ParseException CONNECTION_CLOSED = new ParseException(HttpClientConnection.class, "Connection closed");

	@Nullable
	private SettablePromise<HttpResponse> promise;
	@Nullable
	private HttpResponse response;
	private final AsyncHttpClient client;
	@Nullable
	private final Inspector inspector;

	final InetSocketAddress remoteAddress;
	@Nullable HttpClientConnection addressPrev;
	HttpClientConnection addressNext;
	final int maxBodySize;

	HttpClientConnection(Eventloop eventloop, AsyncHttpClient client,
			AsyncTcpSocket asyncTcpSocket, InetSocketAddress remoteAddress) {
		super(eventloop, asyncTcpSocket);
		this.remoteAddress = remoteAddress;
		this.client = client;
		this.maxBodySize = client.maxBodySize;
		this.inspector = client.inspector;
	}

	@Override
	public void onClosedWithError(@NotNull Throwable e) {
		if (inspector != null) inspector.onHttpError(this, (flags & KEEP_ALIVE) != 0, e);
		if (promise != null) {
			SettablePromise<HttpResponse> promise = this.promise;
			this.promise = null;
			promise.setException(e);
		}
	}

	@Override
	protected void onStartLine(byte[] line, int limit) throws ParseException {
		boolean http1x = line[0] == 'H' && line[1] == 'T' && line[2] == 'T' && line[3] == 'P' && line[4] == '/' && line[5] == '1';
		boolean http11 = line[6] == '.' && line[7] == '1' && line[8] == SP;

		if (!http1x) throw INVALID_RESPONSE;

		int sp1;
		if (http11) {
			flags |= KEEP_ALIVE;
			sp1 = 9;
		} else if (line[6] == '.' && line[7] == '0' && line[8] == SP) {
			sp1 = 9;
		} else if (line[6] == SP) {
			sp1 = 7;
		} else {
			throw new ParseException(HttpClientConnection.class, "Invalid response: " + new String(line, 0, limit, ISO_8859_1));
		}

		int statusCode = decodePositiveInt(line, sp1, 3);
		if (!(statusCode >= 100 && statusCode < 600)) {
			throw new UnknownFormatException(HttpClientConnection.class, "Invalid HTTP Status Code " + statusCode);
		}
		response = new HttpResponse(statusCode);
		response.maxBodySize = maxBodySize;
		/*
		  RFC 2616, section 4.4
		  1.Any response message which "MUST NOT" include a message-body (such as the 1xx, 204, and 304 responses and any response to a HEAD request) is always
		  terminated by the first empty line after the header fields, regardless of the entity-header fields present in the message.
		 */
		int messageCode = response.getCode();
		if ((messageCode >= 100 && messageCode < 200) || messageCode == 204 || messageCode == 304) {
			// Reset Content-Length for the case keep-alive connection
			contentLength = 0;
		}
	}

	@Override
	protected void onHeaderBuf(ByteBuf buf) {
		//noinspection ConstantConditions
		response.addHeaderBuf(buf);
	}

	@Override
	protected void onHeader(HttpHeader header, byte[] array, int off, int len) throws ParseException {
		assert response != null;
		if (response.headers.size() >= MAX_HEADERS) throw TOO_MANY_HEADERS;
		response.addHeader(header, array, off, len);
	}

	@Override
	protected void onHeadersReceived(@Nullable ByteBuf body, @Nullable ChannelSupplier<ByteBuf> bodySupplier) {
		assert !isClosed();

		HttpResponse response = this.response;
		//noinspection ConstantConditions
		response.flags |= MUST_LOAD_BODY;
		response.body = body;
		if (isWebSocket() && response.getCode() == 101) {
			response.bodyStream = concat(ChannelSupplier.of(readQueue.takeRemaining()), ChannelSupplier.ofSocket(socket))
					.withEndOfStream(eos -> eos
							.whenResult(this::onBodyReceived)
							.whenException(e -> {
								if (e instanceof WebSocketException) {
									onBodyReceived();
								} else {
									closeWithError(e);
								}
							}));
			flags |= BODY_RECEIVED;
		} else {
			response.bodyStream = bodySupplier;
		}
		if (inspector != null) inspector.onHttpResponse(this, response);

		SettablePromise<HttpResponse> promise = this.promise;
		this.promise = null;
		//noinspection ConstantConditions
		promise.set(response);
	}

	@Override
	protected void onBodyReceived() {
		assert !isClosed();
		flags ^= BODY_RECEIVED;
		if (response != null && (flags & BODY_SENT) != 0) {
			onHttpMessageComplete();
		}
	}

	@Override
	protected void onBodySent() {
		assert !isClosed();
		flags |= BODY_SENT;
		if (response != null && (flags & BODY_RECEIVED) != 0) {
			onHttpMessageComplete();
		}
	}

	@Override
	protected void onNoContentLength() {
		ChannelSupplier<ByteBuf> ofQueue = readQueue.hasRemaining() ? ChannelSupplier.of(readQueue.takeRemaining()) : ChannelSupplier.of();
		ChannelZeroBuffer<ByteBuf> buffer = new ChannelZeroBuffer<>();
		ChannelSupplier<ByteBuf> supplier = ChannelSuppliers.concat(ofQueue, buffer.getSupplier());
		Promise<Void> inflaterFinished = Promise.complete();
		if ((flags & GZIPPED) != 0) {
			BufsConsumerGzipInflater gzipInflater = BufsConsumerGzipInflater.create();
			supplier = supplier.transformWith(gzipInflater);
			inflaterFinished = gzipInflater.getProcessCompletion();
		}
		onHeadersReceived(null, supplier);
		ChannelSupplier.of(socket::read, socket)
				.streamTo(buffer.getConsumer())
				.both(inflaterFinished)
				.whenComplete(afterProcessCb);
	}

	private @NotNull Promise<HttpResponse> sendWebSocketRequest(HttpRequest request) {
		flags |= WEB_SOCKET;
		flags |= UPGRADE;
		request.addHeader(CONNECTION, CONNECTION_UPGRADE_HEADER);
		request.addHeader(HttpHeaders.UPGRADE, UPGRADE_WEBSOCKET_HEADER);
		request.addHeader(SEC_WEBSOCKET_VERSION, WEB_SOCKET_VERSION);

		byte[] key = new byte[16];
		ThreadLocalRandom.current().nextBytes(key);
		byte[] encodedKey = Base64.getEncoder().encode(key);
		request.addHeader(SEC_WEBSOCKET_KEY, encodedKey);

		WebSocketEncoder encoder = WebSocketEncoder.create(true);
		WebSocketDecoder decoder = WebSocketDecoder.create(encoder, false);
		SettablePromise<Void> successfulUpgrade = new SettablePromise<>();

		ChannelSupplier<ByteBuf> rawOutput = doGetBodyStream(request);
		request.setBodyStream(ChannelSupplier.ofPromise(successfulUpgrade
				.map($ -> rawOutput.transformWith(encoder)
						.withEndOfStream(eos -> eos.then(() -> decoder.getCloseReceivedPromise().toVoid())))));

		writeHttpMessageAsStream(request);

		request.recycle();
		if (!isClosed()) {
			readHttpResponse();
		}
		assert promise != null;
		return promise
				.then(res -> {
					if (res.getCode() != 101 ||
							isHeaderMissing(res, HttpHeaders.UPGRADE, "websocket") ||
							isHeaderMissing(res, CONNECTION, "upgrade") ||
							isAnswerInvalid(res, encodedKey)) {
						close();
						return Promise.ofException(HANDSHAKE_FAILED);
					}

					encoder.getCloseSentPromise().then(decoder::getCloseReceivedPromise)
							.whenException(rawOutput::closeEx)
							.whenResult(rawOutput::closeEx);

					return Promise.of(mapWebSocketResponse(res, decoder, eos -> eos
							.whenResult(() -> encoder.closeEx(REGULAR_CLOSE))
							.whenException(encoder::closeEx)));
				})
				.whenResult(() -> successfulUpgrade.set(null))
				.whenException(rawOutput::closeEx);
	}

	private void readHttpResponse() {
		/*
			as per RFC 7230, section 3.3.3,
			if no Content-Length header is set, client should read body until a server closes the connection
		*/
		contentLength = UNSET_CONTENT_LENGTH;
		if (readQueue.isEmpty()) {
			tryReadHttpMessage();
		} else {
			eventloop.post(() -> {
				if (isClosed()) return;
				tryReadHttpMessage();
			});
		}
	}

	private boolean isAnswerInvalid(HttpResponse response, byte[] key) {
		String header = response.getHeader(SEC_WEBSOCKET_ACCEPT);
		return header == null || !getWebSocketAnswer(new String(key)).equals(header.trim());
	}

	private void onHttpMessageComplete() {
		assert response != null;
		response.recycle();
		response = null;

		if ((flags & KEEP_ALIVE) != 0 && client.keepAliveTimeoutMillis != 0 && contentLength != UNSET_CONTENT_LENGTH) {
			flags = 0;
			socket.read()
					.whenComplete((buf, e) -> {
						if (e == null) {
							if (buf != null) {
								buf.recycle();
								closeWithError(UNEXPECTED_READ);
							} else {
								close();
							}
						} else {
							closeWithError(e);
						}
					});
			if (isClosed()) return;
			client.returnToKeepAlivePool(this);
		} else {
			close();
		}
	}

	/**
	 * Sends the request, recycles it and closes connection in case of timeout
	 *
	 * @param request request for sending
	 */
	public Promise<HttpResponse> send(HttpRequest request) {
		assert !isClosed();
		SettablePromise<HttpResponse> promise = new SettablePromise<>();
		this.promise = promise;
		(pool = client.poolReadWrite).addLastNode(this);
		poolTimestamp = eventloop.currentTimeMillis();
		if (request.getProtocol() == WS || request.getProtocol() == WSS) {
			return sendWebSocketRequest(request);
		}
		HttpHeaderValue connectionHeader = CONNECTION_KEEP_ALIVE_HEADER;
		if (client.keepAliveTimeoutMillis == 0 ||
				client.maxKeepAliveRequests != 0 && ++numberOfKeepAliveRequests >= client.maxKeepAliveRequests) {
			connectionHeader = CONNECTION_CLOSE_HEADER;
		}
		request.addHeader(CONNECTION, connectionHeader);
		ByteBuf buf = renderHttpMessage(request);
		if (buf != null) {
			writeBuf(buf);
		} else {
			writeHttpMessageAsStream(request);
		}
		request.recycle();
		if (!isClosed()) {
			readHttpResponse();
		}
		return promise;
	}

	private void tryReadHttpMessage() {
		try {
			readHttpMessage();
		} catch (ParseException e) {
			closeWithError(e);
		}
	}

	/**
	 * After closing this connection it removes it from its connections cache and recycles
	 * Http response.
	 */
	@Override
	protected void onClosed() {
		if (promise != null) {
			if (inspector != null) inspector.onHttpError(this, (flags & KEEP_ALIVE) != 0, CONNECTION_CLOSED);
			SettablePromise<HttpResponse> promise = this.promise;
			this.promise = null;
			promise.setException(CONNECTION_CLOSED);
		}
		if (pool == client.poolKeepAlive) {
			AddressLinkedList addresses = client.addresses.get(remoteAddress);
			addresses.removeNode(this);
			if (addresses.isEmpty()) {
				client.addresses.remove(remoteAddress);
			}
		}

		// pool will be null if socket was closed by the value just before connection.send() invocation
		// (eg. if connection was in open(null) or taken(null) states)
		//noinspection ConstantConditions
		pool.removeNode(this);

		client.onConnectionClosed();
		if (response != null) {
			response.recycle();
			response = null;
		}
	}

	@Override
	public String toString() {
		return "HttpClientConnection{" +
				"promise=" + promise +
				", response=" + response +
				", httpClient=" + client +
				", keepAlive=" + (pool == client.poolKeepAlive) +
				//				", lastRequestUrl='" + (request.getFullUrl() == null ? "" : request.getFullUrl()) + '\'' +
				", remoteAddress=" + remoteAddress +
				',' + super.toString() +
				'}';
	}
}
