package com.honghu.ai.assigment;

import okhttp3.*;

import java.util.concurrent.TimeUnit;

public class ConnectionTest {
    public static void main(String[] args) {
        System.out.println("=== AutoDL R1 8B 连接测试 ===");
        
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        // 测试1: 检查API是否可达
        testApiAccess(client);
        
        // 测试2: 获取模型列表
        testModelList(client);
        
        // 测试3: 发送实际请求
        testActualRequest(client);
    }

    private static void testApiAccess(OkHttpClient client) {
        System.out.println("\n--- 测试1: 检查API访问 ---");
        String url = "http://127.0.0.1:11434/api/tags";
        
        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("状态码: " + response.code());
            if (response.isSuccessful()) {
                System.out.println("✓ API访问成功");
                System.out.println("响应: " + response.body().string());
            } else {
                System.out.println("✗ API访问失败");
            }
        } catch (Exception e) {
            System.out.println("✗ 连接异常: " + e.getMessage());
        }
    }

    private static void testModelList(OkHttpClient client) {
        System.out.println("\n--- 测试2: 获取模型列表 ---");
        String url = "http://127.0.0.1:11434/api/tags";
        
        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                System.out.println("可用模型:");
                System.out.println(responseBody);
                
                if (responseBody.contains("deepseek-r1")) {
                    System.out.println("✓ 找到 deepseek-r1 模型");
                } else {
                    System.out.println("⚠ 未找到 deepseek-r1 模型");
                }
            }
        } catch (Exception e) {
            System.out.println("获取模型列表失败: " + e.getMessage());
        }
    }

    private static void testActualRequest(OkHttpClient client) {
        System.out.println("\n--- 测试3: 发送实际推理请求 ---");
        String url = "http://127.0.0.1:11434/api/generate";
        
        String json = "{\"model\": \"deepseek-r1:8b\", \"prompt\": \"你好\", \"stream\": false}";

        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("请求状态: " + response.code());
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                System.out.println("✓ 请求成功");
                System.out.println("响应内容: " + responseBody);
            } else {
                System.out.println("✗ 请求失败");
                System.out.println("错误信息: " + response.message());
                if (response.body() != null) {
                    System.out.println("详细错误: " + response.body().string());
                }
            }
        } catch (Exception e) {
            System.out.println("✗ 请求异常: " + e.getMessage());
            System.out.println("请确保:");
            System.out.println("1. SSH隧道正在运行: ssh -L 11434:127.0.0.1:11434 -p 23 root@117.50.195.175");
            System.out.println("2. AutoDL上的服务已启动");
        }
    }
}