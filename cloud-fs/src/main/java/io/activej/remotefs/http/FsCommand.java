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

package io.activej.remotefs.http;

import io.activej.http.HttpPathPart;

public enum FsCommand implements HttpPathPart {
	UPLOAD("upload"),
	DOWNLOAD("download"),
	LIST("list"),
	INFO("info"),
	INFO_ALL("infoAll"),
	PING("ping"),
	DELETE("delete"),
	DELETE_ALL("deleteAll"),
	COPY("copy"),
	COPY_ALL("copyAll"),
	MOVE("move"),
	MOVE_ALL("moveAll");

	private final String pathPart;

	FsCommand(String pathPart) {
		this.pathPart = pathPart;
	}

	@Override
	public String toString() {
		return pathPart;
	}
}
