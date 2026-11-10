package app.milkbank.api

import app.milkbank.session.LogoutReason
import app.milkbank.session.SessionManager
import okhttp3.Interceptor
import okhttp3.Response

const val PASSWORD_CHANGE_REQUIRED_DETAIL = "비밀번호를 먼저 변경해야 합니다"

class AuthInterceptor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = session.state.value?.token
        val request = if (token != null) {
            original.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            original
        }
        val response = chain.proceed(request)
        val isLogin = original.url.encodedPath.endsWith("/auth/login")
        when (response.code) {
            401 -> if (token != null && !isLogin) session.endIfToken(token, LogoutReason.EXPIRED)
            403 -> {
                val body = try {
                    response.peekBody(PEEK_LIMIT).string()
                } catch (_: Exception) {
                    null
                }
                if (parseDetail(body) == PASSWORD_CHANGE_REQUIRED_DETAIL) session.requirePasswordChange()
            }
        }
        return response
    }

    private companion object {
        const val PEEK_LIMIT = 16L * 1024
    }
}
