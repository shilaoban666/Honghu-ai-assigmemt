package com.honghu.ut.test.ai.assigment.testdeepseekr1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration;
import org.springframework.ai.autoconfigure.vectorstore.milvus.MilvusVectorStoreAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        OpenAiAutoConfiguration.class,
        MilvusVectorStoreAutoConfiguration.class
})
@EnableScheduling
public class TestDeepseekR1Application {

    public static void main(String[] args) {
        SpringApplication.run(TestDeepseekR1Application.class, args);
    }

}
