package com.mobiletools.arrears.system.api;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.stereotype.Component;

/** Creates the default account name without changing the exact real-name value used for matching. */
@Component
public class PinyinUsernameGenerator {

    private final HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();

    public PinyinUsernameGenerator() {
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    public String baseUsername(String realName) {
        if (realName == null || realName.isBlank()) {
            throw new IllegalArgumentException("realName is required");
        }
        StringBuilder result = new StringBuilder();
        for (char character : realName.strip().toCharArray()) {
            if (Character.isWhitespace(character) || character == '-' || character == '_') continue;
            if (character < 128 && Character.isLetterOrDigit(character)) {
                result.append(Character.toLowerCase(character));
                continue;
            }
            try {
                String[] syllables = PinyinHelper.toHanyuPinyinStringArray(character, format);
                if (syllables != null && syllables.length > 0) result.append(syllables[0]);
                else if (Character.isLetterOrDigit(character)) result.append(Character.toLowerCase(character));
            } catch (BadHanyuPinyinOutputFormatCombination exception) {
                throw new IllegalStateException("无法生成姓名拼音", exception);
            }
        }
        String username = result.toString();
        if (username.isBlank()) throw new IllegalArgumentException("realName must contain letters or Chinese characters");
        if (username.length() > 100) throw new IllegalArgumentException("generated username is too long");
        return username;
    }
}
