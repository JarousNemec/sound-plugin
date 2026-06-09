package cz.jarda.soundplugin.settings

import javax.swing.table.AbstractTableModel

/** One editable row in the mapping table. */
class MappingRow(
    val eventId: String,
    val displayName: String,
    val isLayerA: Boolean,
    var soundFile: String,   // relative path, or [SoundConfigurable.NONE_LABEL]
    var volume: Int,
    var enabled: Boolean,
    var available: Boolean = true,
)

/**
 * Table model backing the action/event mapping table (PRD §6.1). Columns: event/action, sound,
 * volume, enabled, test. Holds a working copy of the rows; the panel reads them back on apply.
 */
class MappingTableModel(private val rows: MutableList<MappingRow>) : AbstractTableModel() {

    fun rows(): List<MappingRow> = rows

    fun setRows(newRows: List<MappingRow>) {
        rows.clear()
        rows.addAll(newRows)
        fireTableDataChanged()
    }

    fun rowAt(index: Int): MappingRow = rows[index]

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = COLUMN_NAMES.size
    override fun getColumnName(column: Int): String = COLUMN_NAMES[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        COL_VOLUME -> Integer::class.java
        COL_ENABLED -> java.lang.Boolean::class.java
        else -> String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        val row = rows[rowIndex]
        if (!row.available) return false
        return columnIndex == COL_SOUND || columnIndex == COL_VOLUME || columnIndex == COL_ENABLED
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            COL_EVENT -> if (row.available) row.displayName else "${row.displayName} (unavailable)"
            COL_SOUND -> row.soundFile
            COL_VOLUME -> row.volume
            COL_ENABLED -> row.enabled
            COL_TEST -> "▶ Test"
            else -> ""
        }
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val row = rows[rowIndex]
        when (columnIndex) {
            COL_SOUND -> row.soundFile = value?.toString() ?: SoundConfigurable.NONE_LABEL
            COL_VOLUME -> row.volume = (value as? Int ?: (value?.toString()?.toIntOrNull() ?: row.volume)).coerceIn(0, 100)
            COL_ENABLED -> row.enabled = value as? Boolean ?: row.enabled
        }
        fireTableRowsUpdated(rowIndex, rowIndex)
    }

    companion object {
        const val COL_EVENT = 0
        const val COL_SOUND = 1
        const val COL_VOLUME = 2
        const val COL_ENABLED = 3
        const val COL_TEST = 4

        val COLUMN_NAMES = arrayOf("Event / Action", "Sound", "Volume %", "Enabled", "Test")
    }
}
