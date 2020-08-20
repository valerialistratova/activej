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
import io.activej.common.annotation.Beta;
import io.activej.csp.ChannelSupplier;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import static io.activej.http.AbstractHttpConnection.WEB_SOCKET_VERSION;
import static io.activej.http.AsyncServletDecorator.mapResponse;
import static io.activej.http.HttpHeaders.*;
import static io.activej.http.HttpUtils.*;
import static io.activej.http.WebSocketConstants.NOT_A_WEB_SOCKET_REQUEST;
import static io.activej.http.WebSocketConstants.REGULAR_CLOSE;

@Beta
final class WebSocketDecorator implements AsyncServletDecorator {
	private final WebSocketOptions webSocketOptions;

	private WebSocketDecorator(WebSocketOptions webSocketOptions) {
		this.webSocketOptions = webSocketOptions;
	}

	static AsyncServlet webSocket(WebSocketOptions webSocketOptions, AsyncServlet servlet) {
		return new WebSocketDecorator(webSocketOptions).serve(servlet);
	}

	@NotNull
	@Override
	public AsyncServlet serve(@NotNull AsyncServlet servlet) {
		return request -> validateHeaders(request)
				.then(() -> processAnswer(request))
				.then(answer -> {
					WebSocketEncoder encoder = WebSocketEncoder.create(false);
					SettablePromise<Void> successfulUpgrade = new SettablePromise<>();

					WebSocketDecoder decoder = WebSocketDecoder.create(encoder, true);

					ChannelSupplier<ByteBuf> rawStream = request.getBodyStream();
					request.setBodyStream(ChannelSupplier.ofPromise(successfulUpgrade
							.map($ -> rawStream.transformWith(decoder))));
					decoder.getProcessCompletion()
							.whenComplete(($, e) -> {
								if (e == null) {
									encoder.closeEx(REGULAR_CLOSE);
								} else {
									encoder.closeEx(e);
								}
							});

					return servlet.then(mapResponse(response -> {
						if (response.getCode() != 200) {
							return response;
						}

						encoder.useTextEncoding(webSocketOptions.isTextData());
						successfulUpgrade.set(null);

						encoder.getCloseSentPromise().then(decoder::getCloseReceivedPromise)
								.whenException(rawStream::closeEx)
								.whenResult(rawStream::closeEx);

						return webSocketUpgradeResponse(response, answer)
								.withBodyStream(doGetBodyStream(response).transformWith(encoder));
					})).serveAsync(request);
				});
	}

	private static Promise<Void> validateHeaders(HttpRequest request) {
		if (isHeaderMissing(request, UPGRADE, "websocket") ||
				isHeaderMissing(request, CONNECTION, "upgrade") ||
				!Arrays.equals(WEB_SOCKET_VERSION, request.getHeader(SEC_WEBSOCKET_VERSION, ByteBuf::getArray))) {
			return Promise.ofException(NOT_A_WEB_SOCKET_REQUEST);
		}
		return Promise.complete();
	}

	private static Promise<String> processAnswer(HttpRequest request) {
		String header = request.getHeader(SEC_WEBSOCKET_KEY);
		if (header == null) {
			return Promise.ofException(NOT_A_WEB_SOCKET_REQUEST);
		}
		return Promise.of(getWebSocketAnswer(header.trim()));
	}
}
