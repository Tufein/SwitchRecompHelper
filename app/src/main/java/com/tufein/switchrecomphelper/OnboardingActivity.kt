package com.tufein.switchrecomphelper

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.tufein.switchrecomphelper.databinding.ActivityOnboardingBinding

/** Short how-it-works guide: shown on first launch and again from the help button. */
class OnboardingActivity : AppCompatActivity() {
    private class Page(@DrawableRes val image: Int, @StringRes val title: Int, @StringRes val body: Int, val flow: Boolean = false)

    private val pages = listOf(
        // First: what the app does and why, before any of the how.
        Page(R.drawable.ic_cartridge, R.string.onb_what_title, R.string.onb_what_body, flow = true),
        Page(R.drawable.ic_code, R.string.onb_parts_title, R.string.onb_parts_body),
        Page(R.drawable.ic_key, R.string.onb_2_title, R.string.onb_2_body),
        Page(R.drawable.ic_steps, R.string.onb_3_title, R.string.onb_3_body),
        Page(R.drawable.ic_folder, R.string.onb_4_title, R.string.onb_4_body),
        Page(R.drawable.ic_help, R.string.onb_5_title, R.string.onb_5_body),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val b = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = pages.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding, parent, false)) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val p = pages[position]
                holder.itemView.findViewById<ImageView>(R.id.image).setImageResource(p.image)
                holder.itemView.findViewById<TextView>(R.id.title).setText(p.title)
                holder.itemView.findViewById<TextView>(R.id.body).setText(p.body)
                holder.itemView.findViewById<android.view.View>(R.id.flow).visibility =
                    if (p.flow) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        TabLayoutMediator(b.dots, b.pager) { _, _ -> }.attach()

        fun update(position: Int) {
            val last = position == pages.lastIndex
            b.next.setText(if (last) R.string.onb_start else R.string.onb_next)
            b.skip.visibility = if (last) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = update(position)
        })
        update(0)

        b.next.setOnClickListener {
            if (b.pager.currentItem < pages.lastIndex) b.pager.currentItem += 1 else finishOnboarding()
        }
        b.skip.setOnClickListener { finishOnboarding() }
    }

    private fun finishOnboarding() {
        markSeen(this)
        finish()
    }

    companion object {
        private const val PREFS = "app"
        private const val KEY_VERSION = "onboarding_version"

        /** Bump when the guide gets content every user should see once more (also shown again after an update). */
        private const val VERSION = 2

        fun seen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, 0) >= VERSION
        fun markSeen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_VERSION, VERSION) }
    }
}
