package com.a10miaomiao.bilimiao.comm.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.ArrayList

/**
 * **直播**搜索历史（第四阶段，纯新增）。
 *
 * ## 为什么要另起一个库 + 表
 * 用户原话："直播的搜索和我们其他搜索，不要把它们那些历史搜索结果放一起，分开放"。
 * 直播搜的是**主播名 / 直播间标题**（"英雄联盟赛事""某某主播"），全站搜的是
 * **视频/番剧/UP 关键词**；混在一张表里会让两边的"最近搜过"都变得没参考价值
 * （搜完直播回到全站搜索，历史里全是主播名）。
 *
 * ## 为什么是"新类"而不是给 [SearchHistoryDB] 加个参数
 * [SearchHistoryDB] 是**全站搜索**在用的老类（`SearchInputViewModel` 等），它的
 * `DB_NAME`/`TABLE_NAME` 是公开常量、被别处引用。为了让直播这边换个库而改它的构造函数，
 * 等于把"全站搜索的历史逻辑"也一起动了 —— 需求里明确说别动。所以这里另写一份（40 行的 CRUD），
 * 换来的是两边**物理隔离**：库文件名不同、表名不同，谁也不会写脏谁。
 *
 * ## 表结构
 * 只留自增主键 + 关键字两列：直播历史不需要 `type` 分类（全站那张表的 `type` 列
 * 实际也没人写过，默认值恒为 'video'）。
 */
class LiveSearchHistoryDB(
    context: Context,
    name: String,
    factory: SQLiteDatabase.CursorFactory?,
    version: Int,
) : SQLiteOpenHelper(context, name, factory, version) {

    companion object {
        /** ★独立库文件名：和全站搜索的 `PreventKeyWord_db2` 不是同一个文件 */
        const val DB_NAME = "LiveSearchHistory_db"
        /** ★独立表名 */
        const val TABLE_NAME = "LiveSearchHistory"
        private val CREATE_TABLE = """create table if not exists $TABLE_NAME
            |(id integer primary key autoincrement,
            |keyword text)""".trimMargin()
    }

    override fun onCreate(sqLiteDatabase: SQLiteDatabase) {
        sqLiteDatabase.execSQL(CREATE_TABLE)
    }

    override fun onOpen(db: SQLiteDatabase?) {
        // 用 onOpen 而不是只靠 onCreate：库文件存在但表被删过/建失败时，这里还能补上
        // （和 SearchHistoryDB 同一套写法，行为保持一致）
        db?.execSQL(CREATE_TABLE)
    }

    override fun onUpgrade(sqLiteDatabase: SQLiteDatabase, i: Int, i1: Int) {
        // 目前只有 version=1；以后加列/加表在这里升级
    }

    /** 全部历史，**最近搜的在最前**（调用方自己截断到 N 条） */
    fun queryAllHistory(): ArrayList<String> {
        val historys = ArrayList<String>()
        val db = readableDatabase
        val cursor = db.query(TABLE_NAME, null, null, null, null, null, "id desc")
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            val history = cursor.getString(1)
            historys.add(history)
            cursor.moveToNext()
        }
        cursor.close()
        db.close()
        return historys
    }

    fun insertHistory(keyword: String) {
        val db = writableDatabase
        val cv = ContentValues()
        cv.put("keyword", keyword)
        db.insert(TABLE_NAME, null, cv)
        db.close()
    }

    /** 按关键字删一条（调用方"先删后插"保证最近搜的排最前） */
    fun deleteHistory(keyword: String) {
        val db = writableDatabase
        db.delete(TABLE_NAME, "keyword=?", arrayOf(keyword))
        db.close()
    }

    /** 按主键删一条（预留给"长按删除第 N 条"那种交互，和 SearchHistoryDB 的 API 对齐） */
    fun deleteHistory(index: Int) {
        val db = writableDatabase
        db.delete(TABLE_NAME, "id=?", arrayOf(index.toString()))
        db.close()
    }

    fun deleteAllHistory() {
        val db = writableDatabase
        db.execSQL("delete from $TABLE_NAME")
        db.close()
    }
}
