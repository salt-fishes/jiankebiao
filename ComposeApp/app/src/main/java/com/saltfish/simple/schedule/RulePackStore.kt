package com.saltfish.simple.schedule

import android.content.Context
import java.io.File

/**
 * 规则包存取：内置包（代码内定义）+ 导入包（filesDir 的 rulepacks 目录）。
 *
 * 导入即全量校验（结构/安全上限/正则编译），校验失败的包绝不落盘；
 * 指纹选包：解析文本按包打分，取命中最高者，全不中退内置正方包
 * （PDF 路径需要表头锚词）或通用兜底包（图片路径由调用方决定）。
 */
object RulePackStore {

    data class Entry(val pack: ParseRulePack, val builtin: Boolean)

    private fun dir(context: Context): File = File(context.filesDir, "rulepacks").apply { mkdirs() }

    /** 全部可用规则包（内置在前，导入按 id 排序）。 */
    fun listAll(context: Context): List<Entry> {
        val imported = dir(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching {
                    ParseRulePack.fromJson(f.readText()).also { check(it.id == f.nameWithoutExtension) }
                }.getOrNull()?.let { Entry(it, builtin = false) }
            }
            ?.sortedBy { it.pack.id }
            ?: emptyList()
        return ParseRulePack.Builtins.filter { it.id != ParseRulePack.Generic.id }
            .map { Entry(it, builtin = true) } + imported
    }

    /** 导入并持久化；成功返回规则包，失败抛可读异常（调用方展示）。 */
    fun import(context: Context, json: String): ParseRulePack {
        val pack = ParseRulePack.fromJson(json)   // 内含全量校验
        check(!isBuiltin(pack.id)) { "id 与内置规则包重名（${pack.id}），请修改 id 后重试" }
        File(dir(context), "${pack.id}.json").writeText(json)
        return pack
    }

    /** 删除导入的规则包；内置包不可删。 */
    fun remove(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
    }

    fun isBuiltin(id: String): Boolean = ParseRulePack.Builtins.any { it.id == id }

    /** 按 id 取规则包（内置 + 导入）；找不到返回 null（调用方决定兜底）。 */
    fun get(context: Context, id: String): ParseRulePack? =
        listAll(context).firstOrNull { it.pack.id == id }?.pack

    /**
     * 指纹选包：所有候选（内置 + 导入）按命中数打分，取最高分且达标的包。
     * 全不中时：allowGeneric=true 走通用兜底包，否则退内置正方包（PDF 需要
     * 表头锚词才能重建网格，兜底包没有锚词，PDF 场景下只会更早失败）。
     * 同分时导入包优先于内置包（用户显式导入 = 用户意图）。
     */
    fun pickFor(
        context: Context,
        fullText: String,
        allowGeneric: Boolean = false,
    ): ParseRulePack {
        val candidates = if (allowGeneric) {
            listAll(context) + Entry(ParseRulePack.Generic, builtin = true)
        } else {
            listAll(context)
        }
        val best = candidates
            .map { it to it.pack.fingerprintScore(fullText) }
            .filter { (e, score) -> score >= e.pack.matchMinHits }
            .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }
                .thenByDescending { !it.first.builtin })
            .firstOrNull()
        return best?.first?.pack
            ?: if (allowGeneric) ParseRulePack.Generic else ParseRulePack.Zfsoft
    }
}
