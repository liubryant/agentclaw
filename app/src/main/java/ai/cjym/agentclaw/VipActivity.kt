package ai.cjym.agentclaw

import ai.cjym.agentclaw.constants.AppConstants
import ai.cjym.agentclaw.data.repository.AuthService
import ai.cjym.agentclaw.databinding.ActivityVipBinding
import ai.cjym.agentclaw.di.AppGraph
import ai.cjym.agentclaw.pay.AlipayManager
import ai.cjym.agentclaw.pay.PaymentApi
import ai.cjym.agentclaw.pay.VipProduct
import ai.cjym.agentclaw.pay.WeChatPayManager
import ai.cjym.agentclaw.pay.WeChatPayParams
import ai.cjym.agentclaw.quota.QuotaManager
import ai.cjym.agentclaw.util.requireView
import ai.cjym.agentclaw.ui.legal.LegalWebActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class VipActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVipBinding
    private lateinit var paymentApi: PaymentApi
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var products: List<VipProduct> = emptyList()
    private var selectedProduct: VipProduct? = null
    private var selectedChannel = CHANNEL_ALIPAY
    private var currentOrderId: String? = null
    private var orderQueryJob: Job? = null
    private var codeCountdownJob: Job? = null
    private var productViews = mutableListOf<View>()
    private var productCardViews = mutableListOf<LinearLayout>()
    private var productPriceViews = mutableListOf<TextView>()
    private var productNameViews = mutableListOf<TextView>()
    private var productDescViews = mutableListOf<TextView?>()
    private var memberActive = false
    private var memberStatusLoaded = false

    private val badgeTexts = listOf("限时特惠", "超值特惠", "80%用户选择")
    private val badgeStartColors = listOf(
        Color.parseColor("#9C27B0"),
        Color.parseColor("#FFB300"),
        Color.parseColor("#FF6B35")
    )
    private val badgeEndColors = listOf(
        Color.parseColor("#6A1B9A"),
        Color.parseColor("#F57C00"),
        Color.parseColor("#E53935")
    )

    private val wxPayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val errCode = intent.getIntExtra(EXTRA_WX_ERROR_CODE, -1)
            when (errCode) {
                0 -> {
                    binding.vipStatus.setText(R.string.vip_status_confirming_payment)
                    startOrderPolling()
                }
                -2 -> {
                    setPayEnabled(true)
                    binding.vipStatus.setText(R.string.vip_status_payment_cancelled)
                }
                else -> {
                    setPayEnabled(true)
                    binding.vipStatus.setText(R.string.vip_status_payment_incomplete)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVipBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = Color.parseColor("#0D0907")
        // Dark status bar bg → force light/white icons
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }

        paymentApi = PaymentApi(this)
        WeChatPayManager.init(this)

        binding.vipBack.setOnClickListener { finish() }
        binding.vipAgreementCheck.isSelected = false
        binding.vipAgreementCheck.setOnClickListener {
            binding.vipAgreementCheck.isSelected = !binding.vipAgreementCheck.isSelected
        }
        setupAgreementText()

        setupPayChannels()
        binding.vipPayButton.setOnClickListener { startPayment() }
        binding.vipBenefitsCard.setOnClickListener { showBenefitsDetailDialog() }

        val filter = IntentFilter(ACTION_WX_PAY_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wxPayReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wxPayReceiver, filter)
        }

        refreshUserInfo()
        loadProducts()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(wxPayReceiver)
        orderQueryJob?.cancel()
        codeCountdownJob?.cancel()
    }

    private fun setupPayChannels() {
        selectPayChannel(CHANNEL_ALIPAY)
        binding.vipPayChannelAlipay.setOnClickListener { selectPayChannel(CHANNEL_ALIPAY) }
        binding.vipPayChannelWechat.setOnClickListener { selectPayChannel(CHANNEL_WECHAT) }
    }

    private fun selectPayChannel(channel: String) {
        selectedChannel = channel
        val wechatSelected = channel == CHANNEL_WECHAT
        binding.vipPayChannelWechat.isSelected = wechatSelected
        binding.vipPayChannelAlipay.isSelected = !wechatSelected
        binding.vipWechatText.setTextColor(Color.parseColor(if (wechatSelected) "#864F2D" else "#FEEBB9"))
        binding.vipAlipayText.setTextColor(Color.parseColor(if (wechatSelected) "#FEEBB9" else "#864F2D"))
        binding.vipPayChannelWechatCheck.visibility = if (wechatSelected) View.VISIBLE else View.GONE
        binding.vipPayChannelAlipayCheck.visibility = if (wechatSelected) View.GONE else View.VISIBLE
    }

    private fun refreshUserInfo() {
        val prefs = AppGraph.preferences
        if (prefs.isLoggedIn && !prefs.userPhone.isNullOrEmpty()) {
            val phone = prefs.userPhone!!
            val masked = if (phone.length == 11)
                phone.substring(0, 3) + "****" + phone.substring(7)
            else phone
            binding.vipPhone.text = masked
            binding.vipLoginButton.visibility = View.GONE
            memberStatusLoaded = false
            setPayEnabled(false)
            loadMembershipStatus()
        } else {
            memberActive = false
            memberStatusLoaded = true
            binding.vipPhone.setText(R.string.vip_login_required_title)
            binding.vipLoginButton.visibility = View.VISIBLE
            binding.vipLoginButton.setOnClickListener { showLoginDialog { refreshUserInfo() } }
            showPayBottomState()
        }
    }

    private fun loadMembershipStatus() {
        val token = AppGraph.preferences.userAccessToken ?: run {
            memberStatusLoaded = true
            showPayBottomState()
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { paymentApi.getMembership(token) }
            }.onSuccess { data ->
                val isVip = data.optBoolean("active") || data.optBoolean("isVip") || data.optBoolean("isMember")
                val expireDate = data.optString("expiresAt").takeIf { it.isNotEmpty() }
                    ?: data.optString("expireDate").takeIf { it.isNotEmpty() }
                memberActive = isVip
                memberStatusLoaded = true
                if (isVip && expireDate != null) {
                    binding.vipMemberSubtitle.text = getString(R.string.vip_member_active_until, expireDate)
                    binding.vipMemberSubtitle.setTextColor(Color.parseColor("#FFEDBD"))
                    binding.vipStatus.setText(R.string.vip_status_active_can_renew)
                    showPaidBottomState()
                } else {
                    binding.vipMemberSubtitle.setText(R.string.vip_member_subtitle)
                    binding.vipMemberSubtitle.setTextColor(Color.parseColor("#CDAF7A"))
                    binding.vipStatus.setText(if (selectedProduct == null) R.string.vip_status_select_package_first else R.string.vip_status_choose_package)
                    showPayBottomState()
                }
            }.onFailure { e ->
                memberStatusLoaded = true
                binding.vipStatus.text = if (selectedProduct == null) {
                    e.message ?: getString(R.string.vip_status_load_failed)
                } else {
                    getString(R.string.vip_status_choose_package)
                }
                showPayBottomState()
            }
        }
    }

    private fun loadProducts() {
        binding.vipStatus.setText(R.string.vip_status_loading_packages)
        setPayEnabled(false)
        binding.vipProducts.removeAllViews()
        productViews.clear()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { paymentApi.getProducts() }
            }.onSuccess { list ->
                if (list.isEmpty()) {
                    showProductPlaceholder()
                    binding.vipStatus.setText(R.string.vip_status_no_packages)
                } else {
                    products = list
                    renderProducts(list)
                }
            }.onFailure { e ->
                showProductPlaceholder()
                binding.vipStatus.text = getString(R.string.vip_status_tap_retry, e.message ?: getString(R.string.vip_status_load_failed))
                binding.vipProducts.getChildAt(0)?.setOnClickListener { loadProducts() }
            }
        }
    }

    private fun showProductPlaceholder() {
        binding.vipProducts.removeAllViews()
        productViews.clear()
        productCardViews.clear()
        productPriceViews.clear()
        productNameViews.clear()
        productDescViews.clear()
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = getString(R.string.vip_product_loading)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9C8A75"))
            textSize = 14f
            setBackgroundResource(R.drawable.selector_vip_product_bg)
        }
        val params = LinearLayout.LayoutParams((140 * dp).toInt(), (148 * dp).toInt())
        params.setMargins((6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt(), 0)
        binding.vipProducts.addView(tv, params)
        binding.vipBottomPrice.setText(R.string.vip_price_placeholder)
        binding.vipBottomHint.setText(R.string.vip_open_now)
    }

    private fun renderProducts(list: List<VipProduct>) {
        binding.vipProducts.removeAllViews()
        productViews.clear()
        productCardViews.clear()
        productPriceViews.clear()
        productNameViews.clear()
        productDescViews.clear()
        val dp = resources.displayMetrics.density

        list.forEachIndexed { index, product ->
            val wrapper = buildProductCard(product, index, dp)
            val cardView = wrapper.getTag(R.id.vip_product_card) as LinearLayout
            val priceView = wrapper.getTag(R.id.vip_product_price) as TextView
            val nameView = wrapper.getTag(R.id.vip_product_name) as TextView
            val descView = wrapper.getTag(R.id.vip_product_desc) as? TextView

            val outerParams = LinearLayout.LayoutParams((138 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
            outerParams.setMargins((5 * dp).toInt(), 0, (5 * dp).toInt(), 0)
            wrapper.setOnClickListener { selectProduct(index) }

            binding.vipProducts.addView(wrapper, outerParams)
            productViews.add(wrapper)
            productCardViews.add(cardView)
            productPriceViews.add(priceView)
            productNameViews.add(nameView)
            productDescViews.add(descView)
        }

        if (list.isNotEmpty()) {
            selectProduct(0)
            if (!memberStatusLoaded) {
                setPayEnabled(false)
            } else if (memberActive) {
                binding.vipStatus.setText(R.string.vip_status_active_can_renew)
                showPaidBottomState()
            } else {
                binding.vipStatus.setText(if (AppGraph.preferences.isLoggedIn) R.string.vip_status_choose_package else R.string.vip_status_login_required)
            }
        }
    }

    private fun buildProductCard(product: VipProduct, index: Int, dp: Float): FrameLayout {
        val badgeText = badgeTexts.getOrNull(index)
        val badgeStartColor = badgeStartColors.getOrElse(index) { Color.parseColor("#666666") }
        val badgeEndColor = badgeEndColors.getOrElse(index) { Color.parseColor("#444444") }

        val wrapper = FrameLayout(this).apply { clipChildren = false }

        // Card body
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val topPad = if (badgeText != null) (18 * dp).toInt() else (20 * dp).toInt()
            setPadding((10 * dp).toInt(), topPad, (10 * dp).toInt(), (14 * dp).toInt())
            setBackgroundResource(R.drawable.selector_vip_product_bg)
        }

        // Price
        val priceView = TextView(this).apply {
            text = "¥${product.price}"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#EBC783"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }
        card.addView(priceView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Name
        val nameView = TextView(this).apply {
            text = product.name
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#B6A58E"))
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (5 * dp).toInt()
            }.let { layoutParams = it }
        }
        card.addView(nameView)

        // Description
        var descView: TextView? = null
        if (product.description.isNotBlank()) {
            descView = TextView(this).apply {
                text = product.description
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.parseColor("#7A6B58"))
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.topMargin = (4 * dp).toInt()
                }.let { layoutParams = it }
            }
            card.addView(descView)
        }

        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = (16 * dp).toInt()
        }
        wrapper.addView(card, cardParams)

        // Badge floating above card top
        if (badgeText != null) {
            val badgeBg = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(badgeStartColor, badgeEndColor)
            ).apply {
                cornerRadius = 20 * dp
            }
            val badge = TextView(this).apply {
                text = badgeText
                textSize = 9.5f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = badgeBg
                val ph = (5 * dp).toInt()
                val pv = (3 * dp).toInt()
                setPadding(ph * 2, pv, ph * 2, pv + (1 * dp).toInt())
                elevation = 3 * dp
            }
            val badgeParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (4 * dp).toInt()
            }
            wrapper.addView(badge, badgeParams)
        }

        // Store child view refs in wrapper tags for later color updates
        wrapper.setTag(R.id.vip_product_card, card)
        wrapper.setTag(R.id.vip_product_price, priceView)
        wrapper.setTag(R.id.vip_product_name, nameView)
        wrapper.setTag(R.id.vip_product_desc, descView)

        return wrapper
    }

    private fun selectProduct(index: Int) {
        selectedProduct = products.getOrNull(index)
        productCardViews.forEachIndexed { i, card ->
            val isNow = i == index
            card.isSelected = isNow
            productPriceViews.getOrNull(i)?.setTextColor(
                Color.parseColor(if (isNow) "#864F2D" else "#EBC783"))
            productNameViews.getOrNull(i)?.setTextColor(
                Color.parseColor(if (isNow) "#6B3820" else "#B6A58E"))
            productDescViews.getOrNull(i)?.setTextColor(
                Color.parseColor(if (isNow) "#9B5C38" else "#7A6B58"))
        }
        if (!memberStatusLoaded) {
            setPayEnabled(false)
        } else if (memberActive) {
            showPaidBottomState()
        } else {
            binding.vipBottomPrice.text = selectedProduct?.let { "¥${it.price}" } ?: getString(R.string.vip_price_placeholder)
            binding.vipBottomHint.setText(R.string.vip_open_now)
            setPayEnabled(selectedProduct != null)
        }
    }

    private fun setPayEnabled(enabled: Boolean) {
        binding.vipPayButton.isEnabled = enabled && memberStatusLoaded
    }

    private fun showPaidBottomState() {
        binding.vipBottomPrice.text = selectedProduct?.let { "¥${it.price}" } ?: getString(R.string.vip_price_placeholder)
        binding.vipBottomHint.setText(R.string.vip_renew_now)
        setPayEnabled(selectedProduct != null)
    }

    private fun showPayBottomState() {
        binding.vipBottomPrice.text = selectedProduct?.let { "¥${it.price}" } ?: getString(R.string.vip_price_placeholder)
        binding.vipBottomHint.setText(R.string.vip_open_now)
        setPayEnabled(selectedProduct != null)
    }

    private fun showBenefitsDetailDialog() {
        val dialog = BottomSheetDialog(this)
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_vip_upgrade, null, false)
        dialog.setContentView(content)

        content.requireView<TextView>(R.id.upgrade_title).setText(R.string.vip_detail_title)
        content.requireView<TextView>(R.id.upgrade_subtitle).setText(R.string.vip_detail_subtitle)
        content.requireView<View>(R.id.vip_detail_package_section).visibility = View.VISIBLE

        val videoUsed = QuotaManager.todayVideoCount(this)
        val imageUsed = QuotaManager.todayImageCount(this)
        val videoFree = QuotaManager.freeVideoLimit(this)
        val imageFree = QuotaManager.freeImageLimit(this)
        content.requireView<TextView>(R.id.quota_video_text).text = "$videoUsed/$videoFree"
        content.requireView<TextView>(R.id.quota_image_text).text = "$imageUsed/$imageFree"
        content.requireView<TextView>(R.id.quota_video_free_limit).text = getString(R.string.vip_quota_video_per_day, videoFree)
        content.requireView<TextView>(R.id.quota_video_vip_limit).text = getString(R.string.vip_quota_video_per_day, QuotaManager.vipVideoLimit(this))
        content.requireView<TextView>(R.id.quota_image_free_limit).text = getString(R.string.vip_quota_image_per_day, imageFree)
        content.requireView<TextView>(R.id.quota_image_vip_limit).text = getString(R.string.vip_quota_image_per_day, QuotaManager.vipImageLimit(this))
        setDetailQuotaBar(content.requireView(R.id.quota_video_bar), videoUsed, videoFree)
        setDetailQuotaBar(content.requireView(R.id.quota_image_bar), imageUsed, imageFree)

        val productsContainer = content.requireView<LinearLayout>(R.id.vip_detail_products)
        val purchaseButton = content.requireView<Button>(R.id.upgrade_button)
        val detailCards = mutableListOf<LinearLayout>()
        val detailChecks = mutableListOf<TextView>()
        val dp = resources.displayMetrics.density

        fun refreshDetailSelection() {
            val selectedId = selectedProduct?.id
            detailCards.forEachIndexed { index, card ->
                val selected = products.getOrNull(index)?.id == selectedId
                card.background = detailPackageBackground(selected, dp)
                detailChecks.getOrNull(index)?.apply {
                    text = if (selected) "✓" else "○"
                    setTextColor(Color.parseColor(if (selected) "#F6D28F" else "#6E5C4B"))
                }
            }
            purchaseButton.isEnabled = selectedProduct != null
            purchaseButton.alpha = if (selectedProduct != null) 1f else 0.5f
            purchaseButton.text = selectedProduct?.let {
                getString(R.string.vip_detail_purchase, it.price, getString(if (memberActive) R.string.vip_renew_now else R.string.vip_open_now))
            } ?: getString(R.string.vip_select_package)
        }

        if (products.isEmpty()) {
            productsContainer.addView(TextView(this).apply {
                setText(R.string.vip_detail_no_packages)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#988571"))
            }, LinearLayout.LayoutParams((240 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            products.forEachIndexed { index, product ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                }
                val topRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val check = TextView(this).apply {
                    textSize = 18f
                    gravity = Gravity.CENTER
                }
                val name = TextView(this).apply {
                    text = product.name
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding((7 * dp).toInt(), 0, 0, 0)
                    maxLines = 1
                }
                topRow.addView(check, LinearLayout.LayoutParams((24 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
                topRow.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val price = TextView(this).apply {
                    text = "¥${product.price}"
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#F6D28F"))
                    setPadding((31 * dp).toInt(), (5 * dp).toInt(), 0, 0)
                }
                card.addView(price, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (product.description.isNotBlank()) {
                    card.addView(TextView(this).apply {
                    text = product.description
                    textSize = 10f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(Color.parseColor("#988571"))
                        setPadding((31 * dp).toInt(), (2 * dp).toInt(), 0, 0)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                card.setOnClickListener {
                    selectProduct(index)
                    refreshDetailSelection()
                }
                val params = LinearLayout.LayoutParams((164 * dp).toInt(), (86 * dp).toInt()).apply {
                    marginEnd = (10 * dp).toInt()
                }
                productsContainer.addView(card, params)
                detailCards.add(card)
                detailChecks.add(check)
            }
        }

        refreshDetailSelection()
        purchaseButton.setOnClickListener {
            if (selectedProduct == null) return@setOnClickListener
            dialog.dismiss()
            binding.vipPayButton.post { startPayment() }
        }
        content.requireView<TextView>(R.id.upgrade_cancel).apply {
            setText(R.string.vip_detail_close)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            sheet.setBackgroundColor(Color.TRANSPARENT)
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
        dialog.show()
    }

    private fun setDetailQuotaBar(bar: View, used: Int, limit: Int) {
        bar.post {
            val parent = bar.parent as? FrameLayout ?: return@post
            val ratio = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 1f
            val width = (parent.width * ratio).toInt().coerceAtLeast(if (ratio > 0f) 8 else 0)
            bar.layoutParams = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.MATCH_PARENT)
        }
    }

    private fun detailPackageBackground(selected: Boolean, dp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * dp
            setColor(Color.parseColor(if (selected) "#3A291B" else "#21160F"))
            setStroke((if (selected) 2 else 1) * dp.toInt().coerceAtLeast(1),
                Color.parseColor(if (selected) "#D3A85F" else "#483322"))
        }
    }

    private fun setupAgreementText() {
        val full = getString(R.string.vip_agreement_hint)
        val agreementName = getString(R.string.vip_agreement_name)
        val start = full.indexOf(agreementName).takeIf { it >= 0 } ?: 0
        val end = full.length
        val span = SpannableString(full)
        val gold = Color.parseColor("#C87830")
        span.setSpan(ForegroundColorSpan(gold), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(object : ClickableSpan() {
            override fun onClick(widget: android.view.View) {
                startActivity(LegalWebActivity.createIntent(
                    this@VipActivity, agreementName, AppConstants.VIP_AGREEMENT_URL))
            }
            override fun updateDrawState(ds: TextPaint) {
                ds.color = gold
                ds.isUnderlineText = false
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.vipAgreementText.text = span
        binding.vipAgreementText.movementMethod = LinkMovementMethod.getInstance()
        binding.vipAgreementText.highlightColor = Color.TRANSPARENT
    }

    private fun startPayment() {
        if (!binding.vipAgreementCheck.isSelected) {
            AlertDialog.Builder(this)
                .setTitle(R.string.vip_agreement_name)
                .setMessage(R.string.vip_agreement_hint)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    binding.vipAgreementCheck.isSelected = true
                    startPayment()
                }
                .setNegativeButton(R.string.vip_view_agreement) { _, _ ->
                    startActivity(LegalWebActivity.createIntent(
                        this, getString(R.string.vip_agreement_name), AppConstants.VIP_AGREEMENT_URL))
                }
                .show()
            return
        }
        val prefs = AppGraph.preferences
        if (!prefs.isLoggedIn || prefs.userPhone.isNullOrEmpty()) {
            showLoginDialog { startPayment() }
            return
        }
        val token = prefs.userAccessToken
        if (token.isNullOrEmpty()) {
            showLoginDialog { startPayment() }
            return
        }
        if (memberActive) {
            binding.vipStatus.setText(R.string.vip_status_active_can_renew)
            showPaidBottomState()
        }
        if (!memberStatusLoaded) {
            binding.vipStatus.setText(R.string.vip_status_confirming_member)
            setPayEnabled(false)
            return
        }
        val product = selectedProduct
        if (product == null) {
            Toast.makeText(this, getString(R.string.vip_status_select_package_first), Toast.LENGTH_SHORT).show()
            return
        }
        createOrder(token, product)
    }

    private fun createOrder(token: String, product: VipProduct) {
        setPayEnabled(false)
        binding.vipStatus.setText(R.string.vip_status_creating_order)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { paymentApi.createOrder(token, product.id, selectedChannel) }
            }.onSuccess { data ->
                val orderId = data.optString("orderId").takeIf { it.isNotEmpty() }
                    ?: data.optString("id").takeIf { it.isNotEmpty() }
                if (orderId == null) {
                    setPayEnabled(true)
                    binding.vipStatus.setText(R.string.vip_status_order_failed)
                    return@onSuccess
                }
                currentOrderId = orderId
                if (data.optBoolean("mock")) {
                    binding.vipStatus.setText(R.string.vip_status_mock_paid)
                    startOrderPolling()
                    return@onSuccess
                }
                when (selectedChannel) {
                    CHANNEL_ALIPAY -> launchAlipay(data)
                    CHANNEL_WECHAT -> launchWeChatPay(data)
                }
            }.onFailure { e ->
                setPayEnabled(true)
                binding.vipStatus.text = e.message ?: "创建订单失败"
            }
        }
    }

    private fun launchWeChatPay(orderData: JSONObject) {
        val payParams = runCatching { WeChatPayParams.from(orderData) }.getOrNull()
        if (payParams == null || !payParams.isValid()) {
            setPayEnabled(true)
            binding.vipStatus.setText(R.string.vip_status_wechat_error)
            return
        }
        if (!WeChatPayManager.isWeChatInstalled()) {
            setPayEnabled(true)
            binding.vipStatus.setText(R.string.vip_status_wechat_missing)
            return
        }
        if (!WeChatPayManager.pay(this, payParams)) {
            setPayEnabled(true)
            binding.vipStatus.setText(R.string.vip_status_wechat_launch_failed)
        } else {
            binding.vipStatus.setText(R.string.vip_status_wechat_complete)
        }
    }

    private fun launchAlipay(orderData: JSONObject) {
        val orderString = orderData.optString("aliPayOrderString").takeIf { it.isNotEmpty() }
            ?: orderData.optString("orderString").takeIf { it.isNotEmpty() }
            ?: orderData.optString("alipayOrderString").takeIf { it.isNotEmpty() }
        if (orderString.isNullOrEmpty()) {
            setPayEnabled(true)
            binding.vipStatus.setText(R.string.vip_status_alipay_error)
            return
        }
        AlipayManager.pay(this, orderString, object : AlipayManager.PayResultCallback {
            override fun onSuccess() {
                binding.vipStatus.setText(R.string.vip_status_confirming_payment)
                startOrderPolling()
            }
            override fun onProcessing() {
                binding.vipStatus.setText(R.string.vip_status_confirming_payment)
                startOrderPolling()
            }
            override fun onCancelled() {
                setPayEnabled(true)
                binding.vipStatus.setText(R.string.vip_status_payment_cancelled)
            }
            override fun onFailed(msg: String) {
                setPayEnabled(true)
                binding.vipStatus.text = msg
            }
        })
    }

    private fun startOrderPolling() {
        val orderId = currentOrderId ?: return
        val token = AppGraph.preferences.userAccessToken ?: return
        orderQueryJob?.cancel()
        orderQueryJob = scope.launch {
            repeat(6) { attempt ->
                delay(1500L)
                runCatching {
                    withContext(Dispatchers.IO) { paymentApi.queryOrder(token, orderId) }
                }.onSuccess { data ->
                    val status = data.optString("status")
                    if (status.equals("paid", ignoreCase = true) || status.equals("success", ignoreCase = true)) {
                        memberActive = true
                        binding.vipStatus.setText(R.string.vip_status_paid_success)
                        showPaidBottomState()
                        loadMembershipStatus()
                        orderQueryJob?.cancel()
                        return@launch
                    }
                    if (attempt == 5) {
                        setPayEnabled(true)
                        binding.vipStatus.setText(R.string.vip_status_order_processing)
                    }
                }.onFailure {
                    if (attempt == 5) {
                        setPayEnabled(true)
                        binding.vipStatus.setText(R.string.vip_status_order_unknown)
                    }
                }
            }
        }
    }

    private fun showLoginDialog(onLoggedIn: () -> Unit) {
        val dp = resources.displayMetrics.density
        val contentView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
        }
        val phoneInput = EditText(this).apply {
            hint = getString(R.string.vip_login_phone_hint)
            inputType = InputType.TYPE_CLASS_PHONE
            filters = arrayOf(InputFilter.LengthFilter(11))
            setTextColor(Color.BLACK)
        }
        val codeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val codeInput = EditText(this).apply {
            hint = getString(R.string.vip_login_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            setTextColor(Color.BLACK)
        }
        val sendCodeBtn = Button(this).apply {
            setText(R.string.vip_login_get_code)
            textSize = 13f
        }
        codeRow.addView(codeInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        codeRow.addView(sendCodeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentView.addView(phoneInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentView.addView(codeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = (8 * dp).toInt()
        })

        var isSendingCode = false
        sendCodeBtn.setOnClickListener {
            if (isSendingCode) return@setOnClickListener
            val phone = phoneInput.text?.toString()?.trim().orEmpty()
            if (phone.length != 11) {
                Toast.makeText(this, getString(R.string.vip_login_invalid_phone), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isSendingCode = true
            scope.launch {
                AppGraph.authService.sendSmsCode(phone)
                    .onSuccess {
                        Toast.makeText(this@VipActivity, getString(R.string.vip_login_code_sent), Toast.LENGTH_SHORT).show()
                        startCodeCountdown(sendCodeBtn)
                    }
                    .onFailure { e ->
                        isSendingCode = false
                        Toast.makeText(this@VipActivity, e.message ?: getString(R.string.vip_login_send_failed), Toast.LENGTH_SHORT).show()
                    }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.login_title)
            .setView(contentView)
            .setPositiveButton(R.string.login_title, null)
            .setNegativeButton(R.string.setting_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val phone = phoneInput.text?.toString()?.trim().orEmpty()
                val code = codeInput.text?.toString()?.trim().orEmpty()
                if (phone.length != 11) {
                    Toast.makeText(this, getString(R.string.vip_login_invalid_phone), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (code.length != 6) {
                    Toast.makeText(this, getString(R.string.vip_login_invalid_code), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                scope.launch {
                    AppGraph.authService.loginByCode(phone, code)
                        .onSuccess { result ->
                            AppGraph.preferences.isLoggedIn = true
                            AppGraph.preferences.userPhone = result.phone
                            if (result.accessToken.isNotEmpty()) {
                                AppGraph.preferences.userAccessToken = result.accessToken
                            }
                            dialog.dismiss()
                            onLoggedIn()
                        }
                        .onFailure { e ->
                            Toast.makeText(this@VipActivity, e.message ?: getString(R.string.vip_login_failed), Toast.LENGTH_SHORT).show()
                        }
                }
            }
        }
        dialog.show()
    }

    private fun startCodeCountdown(button: Button) {
        codeCountdownJob?.cancel()
        codeCountdownJob = scope.launch {
            for (i in 60 downTo 1) {
                button.text = "${i}s"
                button.isEnabled = false
                delay(1000L)
            }
            button.setText(R.string.vip_login_get_code)
            button.isEnabled = true
        }
    }

    companion object {
        const val ACTION_WX_PAY_RESULT = "ai.cjym.agentclaw.WX_PAY_RESULT"
        const val EXTRA_WX_ERROR_CODE = "wx_error_code"
        const val CHANNEL_WECHAT = "wechat"
        const val CHANNEL_ALIPAY = "alipay"

        fun createIntent(context: Context) = Intent(context, VipActivity::class.java)
    }
}
