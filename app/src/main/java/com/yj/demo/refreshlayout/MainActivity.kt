package com.yj.demo.refreshlayout

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val refreshHead = layoutInflater.inflate(R.layout.refresh_head, null)

        tv_stop.setOnClickListener {

            refresh_layout.refreshComplete()
        }
    }
}
