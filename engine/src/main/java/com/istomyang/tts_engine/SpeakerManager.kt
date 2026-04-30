package com.istomyang.tts_engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SpeakerManager {
    suspend fun list(): Result<List<SpeakerInfo>> {
        val client = HttpClient(CIO) {}

        val res = client.get(urlString = buildUrl()) {
            header("sec-ch-ua-platform", "Windows")
            header("User-Agent", EdgeProtocol.USER_AGENT)
            header(
                "sec-ch-ua",
                "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"${EdgeProtocol.CHROMIUM_MAJOR_VERSION}\", \"Chromium\";v=\"${EdgeProtocol.CHROMIUM_MAJOR_VERSION}\""
            )
            header("sec-ch-ua-mobile", "?0")
            header("Accept", "*/*")
            header("Sec-MS-GEC", DRM.genSecMsGec())
            header("Sec-MS-GEC-Version", EdgeProtocol.SEC_MS_GEC_VERSION)
            header("Sec-Fetch-Site", "none")
            header("Sec-Fetch-Mode", "cors")
            header("Sec-Fetch-Dest", "empty")
            header("Accept-Language", EdgeProtocol.ACCEPT_LANGUAGE)
            header("Accept-Encoding", EdgeProtocol.ACCEPT_ENCODING)
        }

        if (!res.status.isSuccess()) {
            return Result.failure(Throwable(res.status.description))
        }

        client.close()

        val obj = Json.decodeFromString<List<SpeakerInfo>>(res.bodyAsText())
        return Result.success(obj)
    }

    private fun buildUrl(): String {
        return "https://${EdgeProtocol.BASE_URL}/voices/list?trustedclienttoken=${EdgeProtocol.TRUSTED_CLIENT_TOKEN}&Sec-MS-GEC=${DRM.genSecMsGec()}&Sec-MS-GEC-Version=${EdgeProtocol.SEC_MS_GEC_VERSION}"
    }

//    {
//    "Name": "Microsoft Server Speech Text to Speech Voice (en-US, AvaMultilingualNeural)",
//    "ShortName": "en-US-AvaMultilingualNeural",
//    "Gender": "Female",
//    "Locale": "en-US",
//    "SuggestedCodec": "audio-24khz-48kbitrate-mono-mp3",
//    "FriendlyName": "Microsoft AvaMultilingual Online (Natural) - English (United States)",
//    "Status": "GA",
//    "VoiceTag": {
//    "ContentCategories": ["Conversation", "Copilot"],
//    "VoicePersonalities": ["Expressive", "Caring", "Pleasant", "Friendly"]
//    }
//}


    @Serializable
    data class SpeakerInfo(
        @SerialName("Name")
        val name: String,
        @SerialName("ShortName")
        val shortName: String,
        @SerialName("Gender")
        val gender: String,
        @SerialName("Locale")
        val locale: String,
        @SerialName("SuggestedCodec")
        val suggestedCodec: String,
        @SerialName("FriendlyName")
        val friendlyName: String,
        @SerialName("Status")
        val status: String,
        @SerialName("VoiceTag")
        val voiceTag: Tag
    )

    @Serializable
    data class Tag(
        @SerialName("ContentCategories")
        val contentCategories: List<String>,
        @SerialName("VoicePersonalities")
        val voicePersonalities: List<String>
    )

    enum class OutputFormat(val value: String) {
        Webm24Khz16BitMonoOpus("webm-24khz-16bit-mono-opus"),
        Audio24Khz48KbitrateMonoMp3("audio-24khz-48kbitrate-mono-mp3"),
        Audio24Khz96KbitrateMonoMp3("audio-24khz-96kbitrate-mono-mp3"),
    }
}
