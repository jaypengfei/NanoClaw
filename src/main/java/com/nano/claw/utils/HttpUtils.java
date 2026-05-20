package com.nano.claw.utils;

import okhttp3.*;
import java.io.IOException;
import java.util.concurrent.*;

/**
 * HTTP 工具类
 *
 * @author Jason
 * @description 基于 OkHttp 的 HTTP 请求工具
 * @date 2026/5/18
 */
public class HttpUtils {

        private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();

        private static final MediaType MEDIA_TYPE_JSON = MediaType.get("application/json; charset=utf-8");

        /**
         * 发送同步 GET 请求
         *
         * @param url 请求地址
         * @return 响应 JSON 字符串
         * @throws IOException 网络请求异常
         */
        public static String get(String url) throws IOException {
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .get()
                    .build();

            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("GET请求失败，状态码: " + response.code()
                            + "，错误信息: " + response.message());
                }
                if (response.body() == null) {
                    throw new IOException("返回了空的 Body 响应");
                }
                return response.body().string();
            }
        }

        /**
         * 发送同步 GET 请求（带认证）
         *
         * @param url     请求地址
         * @param auth    Authorization 头（如 "Bearer xxx"）
         * @return 响应 JSON 字符串
         * @throws IOException 网络请求异常
         */
        public static String getWithAuth(String url, String auth) throws IOException {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .get();
            if (auth != null && !auth.isEmpty()) {
                builder.addHeader("Authorization", auth);
            }

            try (Response response = CLIENT.newCall(builder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("GET请求失败，状态码: " + response.code());
                }
                if (response.body() == null) {
                    throw new IOException("返回了空的 Body 响应");
                }
                return response.body().string();
            }
        }

        /**
         * 发送同步 POST 请求
         *
         * @param url     请求地址
         * @param apiKey  API Key（可为 null）
         * @param jsonBody 请求体 JSON 字符串
         * @return 响应 JSON 字符串
         * @throws IOException 网络请求异常
         */
        public static String post(String url, String apiKey, String jsonBody) throws IOException {
            RequestBody body = RequestBody.create(jsonBody, MEDIA_TYPE_JSON);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body);

            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
            }

            try (Response response = CLIENT.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("请求失败，状态码: " + response.code()
                            + "，错误信息: " + response.message());
                }
                if (response.body() == null) {
                    throw new IOException("返回了空的 Body 响应");
                }
                return response.body().string();
            }
        }

        /**
         * 发送同步 POST 请求（自定义 Authorization 头）
         *
         * @param url     请求地址
         * @param auth    完整的 Authorization 头（如 "Bearer xxx"）
         * @param jsonBody 请求体 JSON 字符串
         * @return 响应 JSON 字符串
         * @throws IOException 网络请求异常
         */
        public static String postWithAuth(String url, String auth, String jsonBody) throws IOException {
            RequestBody body = RequestBody.create(jsonBody, MEDIA_TYPE_JSON);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body);

            if (auth != null && !auth.isEmpty()) {
                requestBuilder.addHeader("Authorization", auth);
            }

            try (Response response = CLIENT.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("请求失败，状态码: " + response.code()
                            + "，错误信息: " + response.message());
                }
                if (response.body() == null) {
                    throw new IOException("返回了空的 Body 响应");
                }
                return response.body().string();
            }
        }
}
