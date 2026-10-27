package com.healthtrace.rights.consent;

/** 告知文本版本：文案变更时必须升级版本号，历史记录保留当时的版本。 */
public final class ConsentPolicy {

    /** 上传页“发送至百度医疗检验报告单识别”勾选文案的版本。 */
    public static final String OCR_NOTICE_VERSION = "OCR-NOTICE-2026-10";
    /** 建档授权声明版本，与 ProfilePolicy.DECLARATION_VERSION 一致。 */
    public static final String STORAGE_NOTICE_VERSION = "PROFILE-DECL-2026-10";
    public static final String BAIDU_OCR_SCOPE = "BAIDU_OCR";

    private ConsentPolicy() {
    }
}
