package org.tan.cdntest

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MoreActivity : AppCompatActivity() {

    private lateinit var tvCurrentVersion: TextView
    private lateinit var tvLatestVersion: TextView
    private lateinit var layoutLatestVersion: View
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var tvUpdateStatus: TextView
    private lateinit var cardReleaseNotes: MaterialCardView
    private lateinit var tvReleaseNotes: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_more)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvCurrentVersion = findViewById(R.id.tvCurrentVersion)
        tvLatestVersion = findViewById(R.id.tvLatestVersion)
        layoutLatestVersion = findViewById(R.id.layoutLatestVersion)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        cardReleaseNotes = findViewById(R.id.cardReleaseNotes)
        tvReleaseNotes = findViewById(R.id.tvReleaseNotes)

        val updater = UpdateChecker(this)
        tvCurrentVersion.text = updater.getCurrentVersion()

        btnCheckUpdate.setOnClickListener {
            btnCheckUpdate.isEnabled = false
            btnCheckUpdate.text = "检查中..."
            tvUpdateStatus.visibility = View.VISIBLE
            tvUpdateStatus.text = "正在检查更新..."
            cardReleaseNotes.visibility = View.GONE

            updater.checkForUpdate { release ->
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.text = "检查更新"

                if (release != null) {
                    layoutLatestVersion.visibility = View.VISIBLE
                    tvLatestVersion.text = release.tagName
                    tvUpdateStatus.text = "发现新版本"
                    tvUpdateStatus.setTextColor(getColor(R.color.accent))

                    if (release.body.isNotBlank()) {
                        cardReleaseNotes.visibility = View.VISIBLE
                        tvReleaseNotes.text = release.body
                    }

                    updater.showUpdateDialog(release)
                } else {
                    layoutLatestVersion.visibility = View.GONE
                    tvUpdateStatus.text = "已是最新版本"
                    tvUpdateStatus.setTextColor(getColor(R.color.text_secondary))
                }
            }
        }
    }
}
