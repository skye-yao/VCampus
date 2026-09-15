package integration;

import service.CaptchaService;

import java.lang.reflect.Field;
import java.util.Map;

/** Resolves the text behind an in-process image captcha for socket end-to-end tests. */
final class CaptchaTestBridge {
    private CaptchaTestBridge() {
    }

    static String codeFor(String captchaId) {
        try {
            Field cacheField = CaptchaService.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            Object record = ((Map<?, ?>) cacheField.get(CaptchaService.getInstance())).get(captchaId);
            if (record == null) {
                throw new AssertionError("issued captcha must remain available until login");
            }
            Field codeField = record.getClass().getDeclaredField("code");
            codeField.setAccessible(true);
            return String.valueOf(codeField.get(record));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot inspect the issued captcha in the in-process test", failure);
        }
    }
}
