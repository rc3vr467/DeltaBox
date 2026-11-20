package com.deltabox.box.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.deltabox.box.R
import com.deltabox.box.databinding.LayoutNetworkBinding
import com.deltabox.box.ktx.app

class NetworkFragment : NamedFragment(R.layout.layout_network) {

    override fun name0() = app.getString(R.string.tools_network)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutNetworkBinding.bind(view)
        binding.stunTest.setOnClickListener {
            startActivity(Intent(requireContext(), StunActivity::class.java))
        }
    }

}