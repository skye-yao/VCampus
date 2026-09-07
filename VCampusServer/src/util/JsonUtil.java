package util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import java.time.LocalDateTime;

/** Socket JSON 协议：时间统一使用 ISO-8601 字符串。 */
public final class JsonUtil {
    private JsonUtil() {}

    public static Gson createGson() {
        return new GsonBuilder()
                .registerTypeAdapter(LocalDateTime.class,
                        (JsonSerializer<LocalDateTime>) (value, type, context) -> context.serialize(value.toString()))
                .registerTypeAdapter(LocalDateTime.class,
                        (JsonDeserializer<LocalDateTime>) (value, type, context) -> LocalDateTime.parse(value.getAsString()))
                .create();
    }
}
