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
    private class Page(@DrawableRes val image: Int, @StringRes val title: Int, @StringRes val body: Int)

    private val pages = listOf(
        Page(R.drawable.ic_cartridge, R.string.onb_1_title, R.string.onb_1_body),
        Page(R.drawable.ic_key, R.string.onb_2_title, R.string.onb_2_body),
        Page(R.drawable.ic_steps, R.string.onb_3_title, R.string.onb_3_body),
        Page(R.drawable.ic_folder, R.string.onb_4_title, R.string.onb_4_body),
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
        private const val KEY_SEEN = "onboarding_seen"

        fun seen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)
        fun markSeen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_SEEN, true) }
    }
}
