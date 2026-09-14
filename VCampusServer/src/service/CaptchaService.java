package service;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import exception.BusinessException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图形验证码服务
 *
 * 基于 Hutool-captcha 生成抗噪图形验证码，提供验证码生成与一次性防重放校验。
 */
public class CaptchaService {

    private static final CaptchaService INSTANCE = new CaptchaService();

    /** 验证码有效期：2分钟 */
    private static final long EXPIRE_MILLIS = 2 * 60 * 1000L;

    private static class CaptchaRecord {
        final String code;
        final long expireAt;

        CaptchaRecord(String code, long expireAt) {
            this.code = code;
            this.expireAt = expireAt;
        }
    }

    /** 缓存: captchaId -> CaptchaRecord */
    private final Map<String, CaptchaRecord> cache = new ConcurrentHashMap<>();

    private CaptchaService() {
        // 启动后台守护线程定时清理过期验证码
        Thread cleaner = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60 * 1000L);
                    long now = System.currentTimeMillis();
                    cache.entrySet().removeIf(entry -> entry.getValue().expireAt < now);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        }, "captcha-cleaner");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    public static CaptchaService getInstance() {
        return INSTANCE;
    }

    public static class CaptchaVO {
        private final String captchaId;
        private final String imageBase64;

        public CaptchaVO(String captchaId, String imageBase64) {
            this.captchaId = captchaId;
            this.imageBase64 = imageBase64;
        }

        public String getCaptchaId() {
            return captchaId;
        }

        public String getImageBase64() {
            return imageBase64;
        }
    }

    /**
     * 生成新的图形验证码
     *
     * @return 包含唯一标识 captchaId 和 Base64 图片数据的 CaptchaVO
     */
    public CaptchaVO generateCaptcha() {
        // 宽100，高38，4位字符，10条干扰线
        LineCaptcha lineCaptcha = CaptchaUtil.createLineCaptcha(100, 38, 4, 10);
        String code = lineCaptcha.getCode();
        String imageBase64 = lineCaptcha.getImageBase64();

        String captchaId = UUID.randomUUID().toString();
        cache.put(captchaId, new CaptchaRecord(code, System.currentTimeMillis() + EXPIRE_MILLIS));

        return new CaptchaVO(captchaId, imageBase64);
    }

    /**
     * 校验图形验证码（一次性消费机制）
     *
     * @param captchaId 验证码ID
     * @param inputCode 用户输入的验证码
     * @throws BusinessException 验证失败时抛出异常
     */
    public void validateCaptcha(String captchaId, String inputCode) throws BusinessException {
        if (captchaId == null || captchaId.trim().isEmpty()) {
            throw new BusinessException("图形验证码已失效，请点击图片刷新重新获取！");
        }
        if (inputCode == null || inputCode.trim().isEmpty()) {
            throw new BusinessException("请输入图形验证码！");
        }

        // 一次性消费：无论成功失败均移除该 captchaId，防暴力破解与重放
        CaptchaRecord record = cache.remove(captchaId.trim());
        if (record == null || System.currentTimeMillis() > record.expireAt) {
            throw new BusinessException("图形验证码已过期，请点击图片刷新！");
        }

        if (!record.code.equalsIgnoreCase(inputCode.trim())) {
            throw new BusinessException("图形验证码错误，请重新输入！");
        }
    }
}
