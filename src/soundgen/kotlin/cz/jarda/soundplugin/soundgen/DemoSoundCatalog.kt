package cz.jarda.soundplugin.soundgen

/**
 * Single source of truth for the bundled `demo-midi` soundpack: every sound's notes, the event it
 * is mapped to and its default volume. [GenerateDemoPack] renders WAV + MIDI files and writes the
 * pack manifest from this list, so files and mappings can never drift apart.
 *
 * Style rules (keep new sounds consistent):
 *  - Key: D major pentatonic (D E F# A B) for neutral/positive cues. Failures use minor colours in
 *    D (F, C#) or the D–G# tritone, but stay short and soft.
 *  - Every functional group has its own timbre, so even quiet one-note cues are recognisable by
 *    colour, not only by pitch. Groups also differ in gesture: single tick, two-note step,
 *    strum, glissando.
 *  - Instruments are all plucked/struck with a natural decay (no sustained pads), so they blend.
 *    Only the big milestones (build, tests, project open/close) layer several instruments.
 *  - Actions are ticks (<= 200 ms of notes), events 2–3 notes, milestones short arpeggios (<= 700 ms).
 *  - All files are peak-normalised to the same level; loudness is set only by [SoundDef.volume].
 *
 * Instrument → meaning:
 *  - celesta: success              - marimba: failure, destructive edits
 *  - vibraphone: run, search       - kalimba: VCS
 *  - pizzicato strings: clipboard  - harp: history (undo/redo), indexing
 *  - electric piano: navigation    - nylon guitar: code transforms, refactoring
 *  - steel drum: debugger          - music box: notifications
 *  - woodblock: file saved         - flute: IDE window focus
 *  - glockenspiel: intention hint
 */
object DemoSoundCatalog {

    // General MIDI programs (0-based).
    const val E_PIANO = 4
    const val CELESTA = 8
    const val GLOCKENSPIEL = 9
    const val MUSIC_BOX = 10
    const val VIBES = 11
    const val MARIMBA = 12
    const val NYLON_GUITAR = 24
    const val PIZZICATO = 45
    const val HARP = 46
    const val FLUTE = 73
    const val KALIMBA = 108
    const val STEEL_DRUM = 114
    const val WOODBLOCK = 115

    // MIDI note numbers (C4 = 60).
    private const val D3 = 50
    private const val A3 = 57
    private const val C_SHARP4 = 61
    private const val D4 = 62
    private const val F4 = 65
    private const val F_SHARP4 = 66
    private const val G_SHARP4 = 68
    private const val A4 = 69
    private const val B4 = 71
    private const val D5 = 74
    private const val E5 = 76
    private const val F5 = 77
    private const val F_SHARP5 = 78
    private const val A5 = 81
    private const val B5 = 83
    private const val D6 = 86
    private const val E6 = 88
    private const val A6 = 93

    data class Note(
        val startMs: Int,
        val pitch: Int,
        val durationMs: Int,
        val program: Int,
        val velocity: Int = 80,
    )

    data class SoundDef(
        /** File name without extension; produces `sounds/<name>.wav` and `<name>.mid`. */
        val name: String,
        val eventId: String,
        val volume: Int,
        val description: String,
        val notes: List<Note>,
    )

    /** Notes played one after another, [stepMs] apart. */
    private fun seq(program: Int, stepMs: Int, durationMs: Int, velocity: Int, vararg pitches: Int, startMs: Int = 0) =
        pitches.mapIndexed { i, p -> Note(startMs + i * stepMs, p, durationMs, program, velocity) }

    /** Notes struck together. */
    private fun chord(program: Int, durationMs: Int, velocity: Int, vararg pitches: Int, startMs: Int = 0) =
        pitches.map { Note(startMs, it, durationMs, program, velocity) }

    private fun tick(program: Int, pitch: Int, durationMs: Int = 90, velocity: Int = 75, startMs: Int = 0) =
        listOf(Note(startMs, pitch, durationMs, program, velocity))

    /** Layer B: the curated lifecycle events (all 18). */
    val LIFECYCLE: List<SoundDef> = listOf(
        // Build — celesta success over a plucked root; marimba failure.
        SoundDef("build_success", "build.finished.success", 70, "celesta D5-F#5-A5-D6 arpeggio over pizzicato D3",
            tick(PIZZICATO, D3, durationMs = 300, velocity = 70) +
                seq(CELESTA, 90, 260, 80, D5, F_SHARP5, A5, D6)),
        SoundDef("build_failed", "build.finished.failed", 75, "marimba A4-F4-D4 falling, low pizzicato D3 at the end",
            seq(MARIMBA, 110, 220, 85, A4, F4, D4) + tick(PIZZICATO, D3, durationMs = 300, velocity = 70, startMs = 220)),
        // Run — vibraphone; failure marimba.
        SoundDef("run_started", "run.process.started", 45, "vibraphone D5 -> A5",
            seq(VIBES, 90, 160, 70, D5, A5)),
        SoundDef("run_ok", "run.process.terminated.success", 55, "vibraphone dyad A5 + D6",
            chord(VIBES, 300, 70, A5, D6)),
        SoundDef("run_failed", "run.process.terminated", 65, "marimba F4 -> C#4",
            seq(MARIMBA, 130, 220, 80, F4, C_SHARP4)),
        // Debugger — steel drum.
        SoundDef("debug_paused", "debug.breakpoint.hit", 50, "steel drum F#5 -> A5",
            seq(STEEL_DRUM, 110, 250, 75, F_SHARP5, A5)),
        // Tests — celesta run resolved by a harp strum; marimba failure.
        SoundDef("test_passed", "test.finished.passed", 70, "celesta D5-F#5-A5, then harp strum D5-F#5-A5-D6",
            seq(CELESTA, 80, 200, 78, D5, F_SHARP5, A5) +
                seq(HARP, 20, 450, 80, D5, F_SHARP5, A5, D6, startMs = 260)),
        SoundDef("test_failed", "test.finished.failed", 75, "marimba D4-C#4-A3 falling",
            seq(MARIMBA, 120, 220, 85, D4, C_SHARP4, A3)),
        // VCS — kalimba.
        SoundDef("vcs_commit", "vcs.commit.completed", 60, "kalimba A4 -> D5 (stamp)",
            seq(KALIMBA, 100, 250, 85, A4, D5)),
        SoundDef("vcs_update", "vcs.update.completed", 50, "kalimba D5-B4-A4 falling",
            seq(KALIMBA, 90, 220, 80, D5, B4, A4)),
        // Indexing — harp glissando up.
        SoundDef("indexing_done", "indexing.finished", 40, "harp A4-D5-E5-A5 quick glissando up",
            seq(HARP, 45, 250, 68, A4, D5, E5, A5)),
        // Notifications — music box (info/warning), marimba tritone (error).
        SoundDef("notify_info", "notification.shown.info", 30, "music box A5 -> D6",
            seq(MUSIC_BOX, 90, 200, 70, A5, D6)),
        SoundDef("notify_warning", "notification.shown.warning", 50, "music box F5 F5 (repeat)",
            seq(MUSIC_BOX, 120, 150, 75, F5, F5)),
        SoundDef("notify_error", "notification.shown.error", 65, "marimba D5 -> G#4 (tritone)",
            seq(MARIMBA, 120, 220, 85, D5, G_SHARP4)),
        // Files / project / window.
        SoundDef("file_saved", "file.saved", 30, "woodblock tock",
            tick(WOODBLOCK, E5, durationMs = 80, velocity = 75)),
        SoundDef("project_opened", "project.opened", 55, "harp D4-A4, then celesta D5-F#5-A5 (rising roll)",
            seq(HARP, 130, 450, 70, D4, A4) + seq(CELESTA, 130, 400, 72, D5, F_SHARP5, A5, startMs = 260)),
        SoundDef("project_closed", "project.closed", 50, "celesta A5-F#5-D5, then harp A4-D4 (falling roll)",
            seq(CELESTA, 130, 400, 72, A5, F_SHARP5, D5) + seq(HARP, 130, 450, 70, A4, D4, startMs = 390)),
        SoundDef("frame_activated", "app.frame.activated", 20, "flute A5 short breath",
            tick(FLUTE, A5, durationMs = 110, velocity = 60)),
    )

    /** Layer A: a curated subset of IDE actions (action ids from ActionManager). */
    val ACTIONS: List<SoundDef> = listOf(
        // Clipboard — pizzicato strings (cut is destructive → marimba).
        SoundDef("action_copy", "\$Copy", 30, "pizzicato A5 pluck", tick(PIZZICATO, A5, durationMs = 120)),
        SoundDef("action_cut", "\$Cut", 30, "marimba B5 tick", tick(MARIMBA, B5)),
        SoundDef("action_paste", "\$Paste", 30, "pizzicato A5 -> D5 (put down)",
            seq(PIZZICATO, 70, 110, 75, A5, D5)),
        SoundDef("action_duplicate", "EditorDuplicate", 30, "pizzicato D5 double pluck",
            seq(PIZZICATO, 70, 90, 75, D5, D5)),
        SoundDef("action_delete_line", "EditorDeleteLine", 30, "marimba D4 low tick",
            tick(MARIMBA, D4, velocity = 80)),
        // History — harp glissandi.
        SoundDef("action_undo", "\$Undo", 30, "harp D6-A5-F#5 quick glissando down",
            seq(HARP, 35, 150, 70, D6, A5, F_SHARP5)),
        SoundDef("action_redo", "\$Redo", 30, "harp F#5-A5-D6 quick glissando up",
            seq(HARP, 35, 150, 70, F_SHARP5, A5, D6)),
        // Code transforms / refactoring — nylon guitar, each with its own gesture.
        SoundDef("action_comment", "CommentByLineComment", 30, "nylon guitar E5 single pluck",
            tick(NYLON_GUITAR, E5, durationMs = 150, velocity = 75)),
        SoundDef("action_reformat", "ReformatCode", 30, "nylon guitar strum D4-A4-D5-F#5",
            seq(NYLON_GUITAR, 22, 300, 72, D4, A4, D5, F_SHARP5)),
        SoundDef("action_optimize_imports", "OptimizeImports", 30, "nylon guitar A4 -> D5",
            seq(NYLON_GUITAR, 80, 150, 75, A4, D5)),
        SoundDef("action_rename", "RenameElement", 35, "nylon guitar dyad D5 + F#5",
            chord(NYLON_GUITAR, 250, 75, D5, F_SHARP5)),
        SoundDef("action_extract_method", "ExtractMethod", 35, "nylon guitar A4-E5-A5 rising",
            seq(NYLON_GUITAR, 75, 150, 75, A4, E5, A5)),
        SoundDef("action_intention", "ShowIntentionActions", 20, "glockenspiel B5 soft sparkle",
            tick(GLOCKENSPIEL, B5, durationMs = 100, velocity = 55)),
        // Navigation — electric piano; search — vibraphone.
        SoundDef("action_goto_file", "GotoFile", 35, "electric piano D5 -> A5",
            seq(E_PIANO, 70, 130, 72, D5, A5)),
        SoundDef("action_goto_class", "GotoClass", 35, "electric piano D5-F#5-A5",
            seq(E_PIANO, 60, 120, 72, D5, F_SHARP5, A5)),
        SoundDef("action_goto_declaration", "GotoDeclaration", 35, "electric piano A5 -> D5 (dive in)",
            seq(E_PIANO, 70, 130, 72, A5, D5)),
        SoundDef("action_find_in_path", "FindInPath", 30, "vibraphone A5-D6-A6 quick run",
            seq(VIBES, 45, 110, 65, A5, D6, A6)),
        SoundDef("action_search_everywhere", "SearchEverywhere", 30, "vibraphone dyad D6 + A6 shimmer",
            chord(VIBES, 150, 60, D6, A6)),
        // VCS / debugger.
        SoundDef("action_push", "Vcs.Push", 45, "kalimba D5-A5-D6 rising (send off)",
            seq(KALIMBA, 70, 180, 80, D5, A5, D6)),
        SoundDef("action_toggle_breakpoint", "ToggleLineBreakpoint", 30, "steel drum F#5 tick",
            tick(STEEL_DRUM, F_SHARP5, durationMs = 90, velocity = 75)),
    )

    val ALL: List<SoundDef> = LIFECYCLE + ACTIONS
}
