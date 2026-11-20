package app.toque.ui.screens

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.recipes.Origin
import app.toque.core.recipes.SearchIndex
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Toutes les catégories de recettes (Lidl et Cookomix), de la plus fournie à la moins fournie,
 * avec un champ pour les filtrer par nom. Toucher une catégorie revient aux recettes, filtrées.
 */
class CategoriesScreen(
    a: MainActivity,
    private val current: String,
    private var origin: Origin,
    private val onPick: (String, Origin) -> Unit,
) : Screen(a) {
    override val title = tr("Catégories")

    private var query = ""
    private lateinit var tabRow: LinearLayout
    private var shown: List<SearchIndex.CategoryStat> = emptyList()
    private lateinit var count: TextView
    private lateinit var grid: GridView
    private val adapter = Adapter()

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), 0)

        val bar = Ui.horizontal(c)
        tabRow = Ui.horizontal(c)
        bar.addView(tabRow, Ui.lp(Ui.WRAP, Ui.WRAP, r = 14))
        buildTabs()
        val field = Ui.horizontal(c)
        field.setPadding(Ui.u(20), 0, Ui.u(20), 0)
        field.background = Ui.round(Ui.SURFACE, 32f, Ui.STROKE, 1f)
        field.addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(12) })
        val input = EditText(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(21f))
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT3)
            hint = tr("Filtrer les catégories (ex. : dessert)")
            background = null
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a1: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    show()
                }
            })
            setOnEditorActionListener { _, _, _ -> a.hideKeyboard(); true }
        }
        field.addView(input, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        bar.addView(field, LinearLayout.LayoutParams(0, Ui.u(64), 1f))
        count = Ui.text(c, "", 17f, Ui.TEXT2)
        bar.addView(count, Ui.lp(Ui.WRAP, Ui.WRAP, l = 18))
        root.addView(bar, Ui.lp(b = 16))

        grid = GridView(c).apply {
            numColumns = 4
            horizontalSpacing = Ui.u(14)
            verticalSpacing = Ui.u(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, Ui.u(20))
            clipToPadding = false
            adapter = this@CategoriesScreen.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val cat = shown.getOrNull(pos) ?: return@setOnItemClickListener
                a.hideKeyboard()
                onPick(cat.name, origin)
                a.back()
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, state: Int) {
                    if (state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) a.hideKeyboard()
                }
                override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {}
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        show()
        return root
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        val origins = listOf(Origin.ALL, Origin.LIDL, Origin.COOKOMIX)
        tabRow.addView(Ui.tabs(c, listOf(tr("Tout"), "Lidl", "Cookomix"), origins.indexOf(origin)) { i ->
            origin = origins[i]
            buildTabs()
            show()
        })
    }

    private fun show() {
        shown = a.app.recipes.index.findCategories(query, origin)
        count.text = if (shown.size == 1) tr("1 catégorie") else tr("%d catégories", shown.size)
        adapter.notifyDataSetChanged()
    }

    override fun onKnob(delta: Int): Boolean {
        grid.smoothScrollBy(delta * Ui.u(120), 150)
        return true
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? Holder) ?: Holder(c)
            holder.bind(shown[position])
            return holder.root
        }
    }

    private inner class Holder(c: Context) {
        val root = Ui.vertical(c)
        val name = Ui.ellipsize(Ui.text(c, "", 19f, Ui.TEXT, Ui.MEDIUM), 2)
        val meta = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT2))

        init {
            root.tag = this
            root.setPadding(Ui.u(18), Ui.u(16), Ui.u(14), Ui.u(14))
            root.layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, Ui.u(112))
            root.addView(name)
            root.addView(Ui.spacer(c, h = 0, weight = 1f))
            root.addView(meta)
        }

        fun bind(s: SearchIndex.CategoryStat) {
            name.text = s.name
            val n = when (origin) {
                Origin.ALL -> s.total
                Origin.LIDL -> s.lidl
                Origin.COOKOMIX -> s.cookomix
            }
            val parts = ArrayList<String>()
            parts += if (n == 1) tr("1 recette") else tr("%d recettes", n)
            if (origin == Origin.ALL) {
                if (s.lidl > 0 && s.cookomix > 0) parts += tr("%d Lidl · %d Cookomix", s.lidl, s.cookomix)
                else parts += if (s.cookomix > 0) "Cookomix" else "Lidl"
            }
            meta.text = parts.joinToString(" · ")
            val sel = s.name == current
            root.background = Ui.pressable(Ui.round(Ui.SURFACE, 22f, if (sel) Ui.ACCENT else Ui.STROKE, if (sel) 2.5f else 1f), 22f)
        }
    }
}
