package com.example.jactl;

import io.jactl.Jactl;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Demonstrates the Jactl extension model: scripts have NO access to Java —
 * the only way they can reach host functionality is through functions the
 * host explicitly registers here. This is the inverse of Groovy/JS embedding,
 * where you must block things; in Jactl you opt things IN.
 */
@Configuration
public class JactlFunctionsConfig {

    @PostConstruct
    public void registerFunctions() {
        // A global function scripts can call as: serverInfo()
        Jactl.function()
             .name("serverInfo")
             .impl(JactlFunctionsConfig.class, "serverInfo")
             .register();

        // A global function with an argument: rot13('hello')
        Jactl.function()
             .name("rot13")
             .param("text")
             .impl(JactlFunctionsConfig.class, "rot13")
             .register();
    }

    // Implementations must be public static; the trailing field is required
    // scratch space used by Jactl for suspend/resume support.
    public static Object serverInfoData;
    public static String serverInfo() {
        return "java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name")
                + " cpus=" + Runtime.getRuntime().availableProcessors();
    }

    public static Object rot13Data;
    public static String rot13(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 'a' && c <= 'z')      sb.append((char) ('a' + (c - 'a' + 13) % 26));
            else if (c >= 'A' && c <= 'Z') sb.append((char) ('A' + (c - 'A' + 13) % 26));
            else                           sb.append(c);
        }
        return sb.toString();
    }
}
