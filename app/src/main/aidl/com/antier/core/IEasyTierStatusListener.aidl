package com.antier.core;

/** 内核状态事件监听器，事件以 JSON 字符串下发。 */
oneway interface IEasyTierStatusListener {
    void onEvent(String eventJson);
}
