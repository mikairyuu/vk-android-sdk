/*******************************************************************************
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 vk.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/

package com.vk.api.sdk.internal

import android.os.Bundle
import com.vk.api.sdk.exceptions.VKApiCodes
import com.vk.api.sdk.exceptions.VKApiException
import com.vk.api.sdk.exceptions.VKApiExecutionException
import com.vk.api.sdk.exceptions.VKApiIllegalResponseException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object VKErrorUtils {

    fun hasSimpleError(response: JSONObject) = response.has(VKApiCodes.PARAM_ERROR)

    fun hasExecuteError(response: JSONObject, ignoreErrors: IntArray?)
        = when {
            !response.has("execute_errors") -> false
            ignoreErrors == null -> true
            else -> {
                val errors = executeErrorsSet(response)
                ignoreErrors.forEach { errors.remove(it) }
                errors.isNotEmpty()
            }
        }

    private fun executeErrorsSet(json: JSONObject): MutableSet<Int> {
        val set = HashSet<Int>()
        val jaErrors = json.getJSONArray(VKApiCodes.PARAM_EXECUTE_ERRORS)
        for (i in 0 until jaErrors.length()) {
            set.add(jaErrors.getJSONObject(i).getInt(VKApiCodes.PARAM_ERROR_CODE))
        }
        return set
    }

    fun parseSimpleError(errorStr: String, method: String? = null, accessToken: String? = null): VKApiException {
        val errorJson = JSONObject(errorStr).optJSONObject(VKApiCodes.PARAM_ERROR) ?: JSONObject(errorStr)
        return parseSimpleError(errorJson, method, accessToken)
    }

    fun parseSimpleError(errorJson: JSONObject, method: String? = null, accessToken: String? = null): VKApiException {
        try {
            val bundle = when (errorJson.optInt(VKApiCodes.PARAM_ERROR_CODE)) {
                VKApiCodes.CODE_CAPTCHA_REQUIRED -> {
                    val extra = Bundle()
                    extra.putString(VKApiCodes.EXTRA_CAPTCHA_SID, errorJson.getString(VKApiCodes.EXTRA_CAPTCHA_SID))
                    extra.putString(VKApiCodes.EXTRA_CAPTCHA_IMG, errorJson.getString(VKApiCodes.EXTRA_CAPTCHA_IMG))
                    extra
                }
                VKApiCodes.CODE_USER_VALIDATION_REQUIRED -> {
                    val extra = Bundle()
                    extra.putString(VKApiCodes.EXTRA_VALIDATION_URL, errorJson.getString(VKApiCodes.PARAM_REDIRECT_URI))
                    extra
                }
                VKApiCodes.CODE_USER_CONFIRM_REQUIRED -> {
                    val extra = Bundle()
                    extra.putString(VKApiCodes.EXTRA_CONFIRMATION_TEXT, errorJson.getString(VKApiCodes.PARAM_CONFIRM_TEXT))
                    extra
                }
                VKApiCodes.CODE_AUTHORIZATION_FAILED -> {
                    val banInfo = errorJson.optJSONObject(VKApiCodes.PARAM_BAN_INFO)
                    if (banInfo != null) {
                        val extra = Bundle()
                        extra.putString(VKApiCodes.EXTRA_USER_BAN_INFO, banInfo.toString())
                        extra
                    } else null
                }
                VKApiCodes.CODE_ERROR_NEED_TOKEN_EXTENSION -> {
                        val extra = Bundle()
                        extra.putString(VKApiCodes.EXTRA_EXTENSION_HASH, errorJson.optString(VKApiCodes.EXTRA_EXTENSION_HASH, null))
                        extra
                }
                else -> null
            }
            val actualBundle: Bundle?
            if (accessToken != null) {
                actualBundle = bundle ?: Bundle(1)
                actualBundle.putString(VKApiCodes.EXTRA_ACCESS_TOKEN, accessToken)
            } else {
                actualBundle = bundle
            }
            return VKApiExecutionException.parse(errorJson, method, actualBundle)
        } catch (e: Exception) {
            return VKApiIllegalResponseException(errorJson.toString(), e)
        }
    }

    fun parseExecuteError(response: JSONObject, method: String, ignoredErrors: IntArray?) =
            parseExecuteError(response.getJSONArray(VKApiCodes.PARAM_EXECUTE_ERRORS), method, ignoredErrors)

    private fun parseExecuteError(errorsJson: JSONArray, method: String, ignoredErrors: IntArray?): VKApiException {
        try {
            val nonCriticalErrors = ArrayList<VKApiExecutionException>()
            for (i in 0 until errorsJson.length()) {
                val parsedError = parseSimpleError(errorsJson.getJSONObject(i))
                if (parsedError !is VKApiExecutionException) {
                    return parsedError
                }
                when (parsedError.code) {
                    VKApiCodes.CODE_CAPTCHA_REQUIRED,
                    VKApiCodes.CODE_USER_VALIDATION_REQUIRED,
                    VKApiCodes.CODE_USER_CONFIRM_REQUIRED,
                    VKApiCodes.CODE_UNKNOWN_ERROR,
                    VKApiCodes.CODE_INTERNAL_SERVER_ERROR,
                    VKApiCodes.CODE_TOO_MANY_REQUESTS_PER_SECOND,
                    VKApiCodes.CODE_TOO_MANY_SIMILAR_REQUESTS,
                    VKApiCodes.CODE_INVALID_SIGNATURE,
                    VKApiCodes.CODE_TOKEN_CONFIRMATION_REQUIRED,
                    VKApiCodes.CODE_AUTHORIZATION_FAILED -> {
                        return parsedError
                    }
                    else -> {
                        if (ignoredErrors == null || !ignoredErrors.contains(parsedError.code)) {
                            nonCriticalErrors.add(parsedError)
                        }
                    }
                }
            }
            return VKApiExecutionException(
                    VKApiCodes.CODE_COMPOSITE_EXECUTE_ERROR,
                    method,
                    false,
                    "",
                    null,
                    nonCriticalErrors)
        } catch (e: JSONException) {
            return VKApiIllegalResponseException(e)
        }
    }
}