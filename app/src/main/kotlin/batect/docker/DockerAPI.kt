/*
   Copyright 2017-2018 Charles Korn.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package batect.docker

import batect.logging.Logger
import batect.utils.Version
import kotlinx.serialization.json.JSON
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTreeParser
import kotlinx.serialization.json.json
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class DockerAPI(
    private val httpConfig: DockerHttpConfig,
    private val logger: Logger
) {
    fun createContainer(creationRequest: DockerContainerCreationRequest): DockerContainer {
        logger.info {
            message("Creating container.")
            data("request", creationRequest)
        }

        val url = urlForContainers().newBuilder()
            .addPathSegment("create")
            .build()

        val body = creationRequest.toJson()

        val request = Request.Builder()
            .post(jsonRequestBody(body))
            .url(url)
            .build()

        httpConfig.client.newCall(request).execute().use { response ->
            checkForFailure(response) { error ->
                logger.error {
                    message("Container creation failed.")
                    data("error", error)
                }

                throw ContainerCreationFailedException("Output from Docker was: ${error.message}")
            }

            val parsedResponse = JsonTreeParser(response.body()!!.string()).readFully() as JsonObject
            val containerId = parsedResponse["Id"].primitive.content

            logger.info {
                message("Container created.")
                data("containerId", containerId)
            }

            return DockerContainer(containerId)
        }
    }

    fun startContainer(container: DockerContainer) {
        logger.info {
            message("Starting container.")
            data("container", container)
        }

        val request = Request.Builder()
            .post(emptyRequestBody())
            .url(urlForContainerOperation(container, "start"))
            .build()

        httpConfig.client.newCall(request).execute().use { response ->
            checkForFailure(response) { error ->
                logger.error {
                    message("Starting container failed.")
                    data("error", error)
                }

                throw ContainerStartFailedException(container.id, error.message)
            }

            logger.info {
                message("Container started.")
                data("container", container)
            }
        }
    }

    fun inspectContainer(container: DockerContainer): DockerContainerInfo {
        logger.info {
            message("Inspecting container.")
            data("container", container)
        }

        val url = urlForContainer(container).newBuilder()
            .addPathSegment("json")
            .build()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        httpConfig.client.newCall(request).execute().use { response ->
            checkForFailure(response) { error ->
                logger.error {
                    message("Could not inspect container.")
                    data("container", container)
                    data("error", error)
                }

                throw ContainerInspectionFailedException("Could not inspect container '${container.id}': ${error.message}")
            }

            return JSON.nonstrict.parse(response.body()!!.string())
        }
    }

    fun stopContainer(container: DockerContainer) {
        logger.info {
            message("Stopping container.")
            data("container", container)
        }

        val request = Request.Builder()
            .post(emptyRequestBody())
            .url(urlForContainerOperation(container, "stop"))
            .build()

        val clientWithLongTimeout = httpConfig.client.newBuilder()
            .readTimeout(11, TimeUnit.SECONDS)
            .build()

        clientWithLongTimeout.newCall(request).execute().use { response ->
            checkForFailure(response) { error ->
                logger.error {
                    message("Could not stop container.")
                    data("error", error)
                }

                throw ContainerStopFailedException(container.id, error.message)
            }
        }

        logger.info {
            message("Container stopped.")
        }
    }

    fun removeContainer(container: DockerContainer) {
        logger.info {
            message("Removing container.")
            data("container", container)
        }

        val url = urlForContainer(container).newBuilder()
            .addQueryParameter("v", "true")
            .build()

        val request = Request.Builder()
            .delete()
            .url(url)
            .build()

        httpConfig.client.newCall(request).execute().use { response ->
            checkForFailure(response) { error ->
                logger.error {
                    message("Could not remove container.")
                    data("error", error)
                }

                throw ContainerRemovalFailedException(container.id, error.message)
            }
        }

        logger.info {
            message("Container removed.")
        }
    }

    fun waitForNextEventForContainer(container: DockerContainer, eventTypes: Iterable<String>, timeout: Duration): DockerEvent {
        logger.info {
            message("Getting next event for container.")
            data("container", container)
            data("eventTypes", eventTypes)
        }

        val filters = json {
            "event" to eventTypes.toJsonArray()
            "container" to listOf(container.id).toJsonArray()
        }

        val url = baseUrl.newBuilder()
            .addPathSegment("events")
            .addQueryParameter("since", "0")
            .addQueryParameter("filters", filters.toString())
            .build()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        val client = httpConfig.client.newBuilder()
            .readTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS)
            .build()

        client.newCall(request).execute().use { response ->
            checkForFailure(response) { error ->
                logger.error {
                    message("Getting events for container failed.")
                    data("error", error)
                }

                throw DockerException("Getting events for container '${container.id}' failed: ${error.message}")
            }

            val firstEvent = response.body()!!.source().readUtf8LineStrict()
            val parsedEvent = JsonTreeParser(firstEvent).readFully() as JsonObject

            logger.info {
                message("Received event for container.")
                data("event", firstEvent)
            }

            return DockerEvent(parsedEvent["status"].primitive.content)
        }
    }

    fun createNetwork(): DockerNetwork {
        logger.info {
            message("Creating new network.")
        }

        val url = urlForNetworks().newBuilder()
            .addPathSegment("create")
            .build()

        val body = json {
            "Name" to UUID.randomUUID().toString()
            "CheckDuplicate" to true
            "Driver" to "bridge"
        }

        val request = Request.Builder()
            .post(jsonRequestBody(body))
            .url(url)
            .build()

        httpConfig.client.newCall(request).execute().use { response ->
            checkForFailure(response) { error ->
                logger.error {
                    message("Could not create network.")
                    data("error", error)
                }

                throw NetworkCreationFailedException(error.message)
            }

            val parsedResponse = JsonTreeParser(response.body()!!.string()).readFully() as JsonObject
            val networkId = parsedResponse["Id"].primitive.content

            logger.info {
                message("Network created.")
                data("networkId", networkId)
            }

            return DockerNetwork(networkId)
        }
    }

    fun deleteNetwork(network: DockerNetwork) {
        logger.info {
            message("Deleting network.")
            data("network", network)
        }

        val request = Request.Builder()
            .delete()
            .url(urlForNetwork(network))
            .build()

        httpConfig.client.newCall(request).execute().use { response ->
            checkForFailure(response) { error ->
                logger.error {
                    message("Could not delete network.")
                    data("error", error)
                }

                throw NetworkDeletionFailedException(network.id, error.message)
            }
        }

        logger.info {
            message("Network deleted.")
        }
    }

    fun getServerVersionInfo(): DockerVersionInfo {
        logger.info {
            message("Getting Docker version information.")
        }

        val url = baseUrl.newBuilder()
            .addPathSegment("version")
            .build()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        httpConfig.client.newCall(request).execute().use { response ->
            checkForFailure(response) { error ->
                logger.error {
                    message("Could not get Docker version info.")
                    data("error", error)
                }

                throw DockerVersionInfoRetrievalException("The request failed: ${error.message}")
            }

            val parsedResponse = JsonTreeParser(response.body()!!.string()).readFully() as JsonObject
            val version = parsedResponse["Version"].primitive.content
            val apiVersion = parsedResponse["ApiVersion"].primitive.content
            val minAPIVersion = parsedResponse["MinAPIVersion"].primitive.content
            val gitCommit = parsedResponse["GitCommit"].primitive.content

            return DockerVersionInfo(
                Version.parse(version),
                apiVersion,
                minAPIVersion,
                gitCommit
            )
        }
    }

    private val baseUrl: HttpUrl = httpConfig.baseUrl.newBuilder()
        .addPathSegment("v1.12")
        .build()

    private fun urlForContainers(): HttpUrl = baseUrl.newBuilder()
        .addPathSegment("containers")
        .build()

    private fun urlForContainer(container: DockerContainer): HttpUrl = urlForContainers().newBuilder()
        .addPathSegment(container.id)
        .build()

    private fun urlForContainerOperation(container: DockerContainer, operation: String): HttpUrl = urlForContainer(container).newBuilder()
        .addPathSegment(operation)
        .build()

    private fun urlForNetworks(): HttpUrl = baseUrl.newBuilder()
        .addPathSegment("networks")
        .build()

    private fun urlForNetwork(network: DockerNetwork): HttpUrl = urlForNetworks().newBuilder()
        .addPathSegment(network.id)
        .build()

    private fun emptyRequestBody(): RequestBody = RequestBody.create(MediaType.get("text/plain"), "")

    private val jsonMediaType = MediaType.get("application/json")
    private fun jsonRequestBody(json: JsonObject): RequestBody = jsonRequestBody(json.toString())
    private fun jsonRequestBody(json: String): RequestBody = RequestBody.create(jsonMediaType, json)

    private inline fun checkForFailure(response: Response, errorHandler: (DockerAPIError) -> Unit) {
        if (response.isSuccessful) {
            return
        }

        val responseBody = response.body()!!.string()
        val contentType = response.body()!!.contentType()!!

        if (contentType.type() != jsonMediaType.type() || contentType.subtype() != jsonMediaType.subtype()) {
            logger.warn {
                message("Error response from Docker daemon was not in JSON format.")
                data("statusCode", response.code())
                data("message", responseBody)
            }

            errorHandler(DockerAPIError(response.code(), responseBody))
            return
        }

        val parsedError = JsonTreeParser(responseBody).readFully() as JsonObject
        errorHandler(DockerAPIError(response.code(), parsedError["message"].primitive.content))
    }

    private data class DockerAPIError(val statusCode: Int, val message: String)
}
