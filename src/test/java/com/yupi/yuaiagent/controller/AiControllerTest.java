package com.yupi.yuaiagent.controller;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class AiControllerTest {
    @Resource
    AiController aiController;
    @Test
    void test(){
        aiController.doChatWithLoveAppSync("你好，我目前没有女朋友","10");
    }
}
