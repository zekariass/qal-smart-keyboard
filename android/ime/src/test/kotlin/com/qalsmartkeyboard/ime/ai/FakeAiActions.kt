package com.qalsmartkeyboard.ime.ai

/** Records every call; answers are scripted by the test. */
class FakeAiActions : AiActions {
    class Submitted(val request: AiRequest, val onOutcome: (AiOutcome) -> Unit, var cancelled: Boolean = false)

    val submitted = ArrayList<Submitted>()
    var refreshCount = 0
    var availabilityReads = 0
    var destinationsList = listOf(AiDestination("lang:en", "English", "en"), AiDestination("p1", "Mum", "am", isProfile = true))
    var defaultFor: (String?) -> AiDestination = { destinationsList.first() }
    var appRule: (String?) -> AiDestination? = { null }
    private val listeners = ArrayList<() -> Unit>()

    /** When set, [submit] answers at once (synchronously) with this. */
    var autoAnswer: AiOutcome? = null

    override var availability = AiAvailability()
        get() { availabilityReads++; return field }

    override fun destinations() = destinationsList

    override fun defaultDestination(packageName: String?) = defaultFor(packageName)

    override fun appRuleDestination(packageName: String?) = appRule(packageName)

    val remembered = ArrayList<Pair<String?, AiDestination>>()

    override fun rememberDestination(packageName: String?, destination: AiDestination) { remembered += packageName to destination }

    override fun submit(request: AiRequest, onOutcome: (AiOutcome) -> Unit): AiCall {
        val s = Submitted(request, onOutcome)
        submitted += s
        autoAnswer?.let(onOutcome)
        return AiCall { s.cancelled = true }
    }

    override fun refresh() { refreshCount++ }

    override fun addAvailabilityListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun fireAvailabilityChanged() = listeners.toList().forEach { it() }

    fun answer(index: Int, outcome: AiOutcome) = submitted[index].onOutcome(outcome)

    companion object {
        fun ok(text: String, vararg alternatives: AiAlternative) = AiOutcome.Success(AiResult(text, alternatives.toList()))
        fun fail(f: AiFailure) = AiOutcome.Failure(f)
    }
}
