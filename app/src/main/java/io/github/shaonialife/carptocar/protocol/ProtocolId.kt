package io.github.shaonialife.carptocar.protocol

/**
 * 协议模块身份。新增协议时在这里加一项，再到 [ProtocolRegistry] 注册对应实现。
 *
 * 用枚举而不是字符串：provides/consumes 的声明在编译期就能对上，拼错 UUID 式的
 * 字符串只会在真机上表现为「事件没人接」。
 */
enum class ProtocolId {
    CARPLAY,
    CARLIFE,

    /** 系统来源（通知使用权拿到的媒体会话、系统按键等），不是协议模块，永不注册实现。 */
    SYSTEM,
}
