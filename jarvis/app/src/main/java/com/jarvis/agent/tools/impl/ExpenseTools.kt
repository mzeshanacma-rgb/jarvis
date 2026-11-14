package com.jarvis.agent.tools.impl

import com.jarvis.agent.brain.double
import com.jarvis.agent.brain.str
import com.jarvis.agent.expenses.Expense
import com.jarvis.agent.expenses.ExpenseCategories
import com.jarvis.agent.expenses.Periods
import com.jarvis.agent.tools.BaseTool
import com.jarvis.agent.tools.RiskLevel
import com.jarvis.agent.tools.ToolContext
import com.jarvis.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AddExpenseTool : BaseTool(
    "add_expense", "Record money the user spent (e.g. \"I paid 1200 for fuel\").", RiskLevel.LOW,
    schemaBlock = {
        number("amount", "Amount spent")
        string("merchant", "Where / to whom")
        string("category", "Category", required = false, enum = ExpenseCategories.all)
        string("currency", "ISO code, default PKR", required = false)
        string("date", "yyyy-MM-dd, default today", required = false)
        string("note", "Optional note", required = false)
    },
) {
    override fun describe(input: JsonObject) = "Add expense ${input.double("amount")} at ${input.str("merchant")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val amount = input.double("amount") ?: return ToolResult.error("amount required")
        val date = input.str("date")?.let { runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(it)?.time }.getOrNull() }
        val e = Expense(
            merchant = input.str("merchant").orEmpty().ifBlank { "Unknown" },
            amount = amount,
            currency = input.str("currency")?.uppercase()?.take(3) ?: "PKR",
            category = ExpenseCategories.normalize(input.str("category")),
            date = date ?: System.currentTimeMillis(),
            note = input.str("note").orEmpty(),
        )
        ctx.graph.expenseDb.dao().insert(e)
        return ToolResult.ok("Recorded ${e.currency} ${"%.0f".format(e.amount)} at ${e.merchant} (${e.category}).")
    }
}

class SpendingSummaryTool : BaseTool(
    "spending_summary", "How much the user spent in a period, by category, with the latest expenses.", RiskLevel.LOW, readOnly = true,
    { string("period", "Which period (default this_month)", required = false, enum = listOf("today", "this_week", "this_month", "last_month", "this_year")) },
) {
    override fun describe(input: JsonObject) = "Spending ${input.str("period") ?: "this_month"}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val (from, to) = Periods.range(input.str("period") ?: "this_month")
        val dao = ctx.graph.expenseDb.dao()
        val totals = dao.totals(from, to)
        if (totals.isEmpty()) return ToolResult.ok("No expenses recorded in that period.")
        val sum = totals.sumOf { it.total }
        val recent = dao.between(from, to).take(5)
        val fmt = SimpleDateFormat("d MMM", Locale.ENGLISH)
        return ToolResult.ok(
            "Total: ${"%.0f".format(sum)}\nBy category: " + totals.joinToString(", ") { "${it.category} ${"%.0f".format(it.total)}" } +
                "\nLatest: " + recent.joinToString("; ") { "${fmt.format(Date(it.date))} ${it.merchant} ${it.currency} ${"%.0f".format(it.amount)}" },
        )
    }
}
