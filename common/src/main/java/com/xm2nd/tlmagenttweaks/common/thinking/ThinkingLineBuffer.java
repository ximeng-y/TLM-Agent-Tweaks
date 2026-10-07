package com.xm2nd.tlmagenttweaks.common.thinking;

/**
 * 思考链展示用的单行有界缓冲。
 * <p>
 * 只保留「当前逻辑行」，不保存思考全文或历史行：换行符一到就丢弃整行旧内容，
 * 因此内存占用与思考长度无关，恒为一行。超出上限时从行首丢弃多余码点，
 * 且不会在代理对（surrogate pair）中间切断。
 * <p>
 * 这是**展示缓冲**：不得据此裁剪协议聚合结果、模型历史或任何统计口径。
 */
public final class ThinkingLineBuffer {

    /** 单行保留的最大 Unicode 码点数量。 */
    public static final int MAX_CODE_POINTS = 2048;

    private final StringBuilder line = new StringBuilder();
    /** 当前行内容未执行过的换行分隔符残留长度（0 或 1），用于跨增量识别 \r\n。 */
    private boolean pendingCarriageReturn;

    /**
     * 追加一段模型增量。
     * <p>
     * 支持 {@code \n}、{@code \r\n} 与单独 {@code \r}；一个增量内出现多次换行时，
     * 最终状态为最后一个换行之后的内容（前面的行不会以历史形式保留）。
     */
    public void append(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        int length = delta.length();
        for (int i = 0; i < length; i++) {
            char c = delta.charAt(i);
            if (c == '\n') {
                line.setLength(0);
                pendingCarriageReturn = false;
                continue;
            }
            if (c == '\r') {
                // \r 本身先当换行处理；紧随其后的 \n 会再清空一次，结果等价，且空行不被多算。
                line.setLength(0);
                pendingCarriageReturn = true;
                continue;
            }
            if (pendingCarriageReturn) {
                // \r 之后不是 \n：\r 已经换过行，此处直接写新字符。
                pendingCarriageReturn = false;
            }
            line.append(c);
        }
        trimToBound();
    }

    /** 清空当前行（回退、新一轮思考或终态清理）。 */
    public void clear() {
        line.setLength(0);
        pendingCarriageReturn = false;
    }

    /** 当前行内容；无内容时返回空串，调用方据此决定是否仍有思考文字可显示。 */
    public String line() {
        return line.toString();
    }

    public boolean isEmpty() {
        return line.length() == 0;
    }

    /**
     * 按**码点**裁剪到 {@link #MAX_CODE_POINTS} 以内，保留行尾（最新内容）。
     * 裁剪点落在代理对中间时向前多让一个 char，保证不产生孤立代理。
     */
    private void trimToBound() {
        if (line.length() <= MAX_CODE_POINTS) {
            // 码点数不可能超过 UTF-16 单元数，此处必然满足上限。
            return;
        }
        int from = line.length() - MAX_CODE_POINTS;
        if (Character.isLowSurrogate(line.charAt(from)) && Character.isHighSurrogate(line.charAt(from - 1))) {
            from++;
        }
        line.delete(0, from);
    }
}
