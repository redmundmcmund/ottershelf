package io.github.ottershelf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.github.ottershelf.core.model.NativeCredentials
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.FakeServer
import io.github.ottershelf.core.session.Session
import io.github.ottershelf.core.session.TokenCipher
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The cover loader's downloads ([coverFetcher]): the account's bearer token, on a queue of their own. */
@RunWith(AndroidJUnit4::class)
class CoverFetcherTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun coversCarryTheTokenAndDoNotWaitBehindTheApiCalls() = FakeServer().use { server ->
        val release = CountDownLatch(1)
        server.answer = { request ->
            if (request.path.endsWith("/slow")) release.await(20, TimeUnit.SECONDS)
            FakeServer.Answer(200, "not an image")
        }
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        val session = Session(context, object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(stored: String) = stored
        })
        session.store(
            NativeCredentials(
                accessToken = "access-1",
                accessTokenExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "refresh-1",
                refreshTokenExpiresAt = "2099-01-01T00:00:00Z",
            ),
            server = server.url,
        )
        val api = Api(session, "Test device") {}
        val loader = ImageLoader.Builder(context)
            .components { add(coverFetcher { api.client }) }
            .memoryCache(null)
            .diskCache(null)
            .build()
        try {
            // Every one of the Api client's requests to the server under way (OkHttp's default, 5).
            repeat(5) {
                api.client.newCall(Request.Builder().url("${server.url}/api/v1/slow").build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = Unit
                    override fun onResponse(call: Call, response: Response) = response.close()
                })
            }
            val cover = "${server.url}/api/v1/books/1/thumbnail?t=1"

            runBlocking { withTimeout(10_000) { loader.execute(ImageRequest.Builder(context).data(cover).build()) } }

            val fetched = server.received.single { it.path.endsWith("/thumbnail?t=1") }
            assertEquals("Bearer access-1", fetched.authorization)
        } finally {
            release.countDown()
            loader.shutdown()
        }
    }
}
