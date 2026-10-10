package com.yupi.yuaiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy; // 注意导包
import org.objenesis.strategy.StdInstantiatorStrategy; // 注意导包

/**
 * 基于本地文件系统的聊天记忆存储仓库 (Kryo 二进制高性能版)
 * 每个 conversationId 对应一个极其紧凑的 .bin 二进制文件
 */
public class KryoFileBasedChatMemoryRepository implements ChatMemoryRepository {

    private final String storageDirectory;

    /**
     * 【核心护城河】：使用 ThreadLocal 解决 Kryo 的线程不安全问题。
     * 确保 Tomcat 的每一个处理请求的线程，都拥有一个属于自己的、独立的 Kryo 实例。
     */
    private final ThreadLocal<Kryo> kryoThreadLocal = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);

        // 【新增的核心救场代码：启用 Objenesis 策略】
        // 告诉 Kryo：如果找不到无参构造器，就用底层黑魔法强行分配内存造对象！
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));

        return kryo;
    });

    public KryoFileBasedChatMemoryRepository(String storageDirectory) {
        Assert.hasText(storageDirectory, "Storage directory cannot be empty");
        this.storageDirectory = storageDirectory;
        
        File dir = new File(storageDirectory);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                throw new RuntimeException("无法创建 Kryo 记忆存储目录: " + storageDirectory);
            }
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        File file = getMemoryFile(conversationId);
        Kryo kryo = kryoThreadLocal.get(); // 获取当前线程专属的 Kryo 实例
        
        // 使用 Kryo 专属的 Output 包装文件输出流 (TWR 语法自动关闭流)
        try (Output output = new Output(new FileOutputStream(file))) {
            // 由于 messages 实际上是一个 ArrayList，直接将整个 List 序列化进二进制流
            kryo.writeObject(output, new ArrayList<>(messages));
        } catch (Exception e) {
            throw new RuntimeException("持久化聊天记忆到二进制文件失败, conversationId: " + conversationId, e);
        }
    }

    @Override
    public List<String> findConversationIds() {
        File dir = new File(storageDirectory);
        // 定义我们存储文件时使用的统一后缀
        String suffix = "_memory.bin";

        // 1. 利用 FileFilter 过滤出当前目录下所有合法的记忆文件
        File[] files = dir.listFiles((d, name) -> name.endsWith(suffix));

        List<String> conversationIds = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                String fileName = file.getName();
                // 2. 截取掉后缀，精准还原出原始的 conversationId
                // 使用 substring 比 replace 更安全，防止 ID 本身刚好包含后缀字符串
                String id = fileName.substring(0, fileName.length() - suffix.length());
                conversationIds.add(id);
            }
        }

        return conversationIds;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Message> findByConversationId(String conversationId) {
        File file = getMemoryFile(conversationId);
        if (!file.exists()) {
            return new ArrayList<>(); // 全新对话，返回空集合
        }
        
        Kryo kryo = kryoThreadLocal.get();
        // 使用 Kryo 专属的 Input 包装文件输入流
        try (Input input = new Input(new FileInputStream(file))) {
            // 从二进制流中原地复活 ArrayList
            return kryo.readObject(input, ArrayList.class);
        } catch (Exception e) {
            throw new RuntimeException("从二进制文件读取聊天记忆失败, conversationId: " + conversationId, e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        File file = getMemoryFile(conversationId);
        if (file.exists()) {
            file.delete();
        }
    }

    private File getMemoryFile(String conversationId) {
        // 注意：后缀改成了 .bin，表明这是一个无法用记事本打开的二进制文件
        return new File(storageDirectory, conversationId + "_memory.bin");
    }
}