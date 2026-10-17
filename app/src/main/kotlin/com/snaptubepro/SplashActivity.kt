package com.ericcatch

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.ericcatch.databinding.ActivitySplashBinding

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Versión
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "1.0" }
        binding.splashVersion.text = "v$version"

        animateIn()
    }

    private fun animateIn() {
        // Logo: scale desde 0 con overshoot
        binding.splashLogo.apply {
            scaleX = 0f; scaleY = 0f; alpha = 0f
            animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(600)
                .setStartDelay(100)
                .setInterpolator(OvershootInterpolator(1.6f))
                .start()
        }
        // Contenedor completo: fade in
        binding.splashContent.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(100)
            .setInterpolator(DecelerateInterpolator())
            .start()
        // Versión: fade in tarde
        binding.splashVersion.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(500)
            .start()

        // Navegar a MainActivity después de la animación
        binding.root.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            // Transición suave: fade out del splash
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 1400)
    }
}
