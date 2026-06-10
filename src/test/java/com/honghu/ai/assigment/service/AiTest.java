package com.honghu.ai.assigment.service;

import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit; // 记得导入这个包

public class AiTest {
    public static void main(String[] args) throws IOException {
        // 关键改动：自定义超时时间
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS) // 连接超时：60秒
                .readTimeout(5, TimeUnit.MINUTES)    // 读取超时：5分钟（给 AI 足够的思考时间）
                .writeTimeout(60, TimeUnit.SECONDS)  // 写入超时：60秒
                .build();

        String url = "http://127.0.0.1:11434/api/generate"; // 通过SSH隧道连接到AutoDL
        // 注意：stream 设置为 true 时，可以实时接收响应；false 时等待完整响应
        String json = "{\"model\": \"deepseek-r1:8b\", \"prompt\": \"用一句话鼓励一个正在低谷期拼命敲代码的28岁程序员\", \"stream\": false}";

        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(url).post(body).build();

        System.out.println("正在通过SSH隧道连接到AutoDL上的R1 8B模型...");
        System.out.println("提示: 确保SSH隧道已启动 - ssh -L 11434:127.0.0.1:11434 -p 23 root@117.50.195.175");

        try (Response response = client.newCall(request).execute()) {
            System.out.println("连接状态: " + response.code());
            if (!response.isSuccessful()) {
                System.err.println("请求失败: " + response.code());
                System.err.println("错误信息: " + response.message());
                if (response.body() != null) {
                    System.err.println("响应体: " + response.body().string());
                }
                return;
            }
            System.out.println("AI 的回答是：");
            String responseBody = response.body().string();
            System.out.println(responseBody);
            
            // 如果是流式响应，可能需要持续读取
            if (responseBody.contains("\"stream\":true")) {
                System.out.println("检测到流式响应，继续读取...");
                // 这里可以根据实际API响应格式进行处理
            }
        } catch (Exception e) {
            System.err.println("连接异常，请检查：");
            System.err.println("1. SSH隧道是否正常运行");
            System.err.println("2. AutoDL服务是否启动");
            System.err.println("3. 网络连接是否正常");
            e.printStackTrace();
        }
    }
}