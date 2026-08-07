package com.antier.core;

/** 配置服务器远程配置应用/删除事件回调。 */
oneway interface IConfigServerEventCallback {
    void onEvent(String eventJson);
}
