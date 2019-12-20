package com.twilio.chat.demo.activities

import java.util.Comparator
import java.util.HashMap
import java.util.Random
import com.twilio.chat.Channel
import com.twilio.chat.Channel.ChannelType
import com.twilio.chat.ChannelDescriptor
import com.twilio.chat.CallbackListener
import com.twilio.chat.ChatClientListener
import com.twilio.chat.ChatClient
import com.twilio.chat.ErrorInfo
import com.twilio.chat.User
import com.twilio.chat.Paginator
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.*
import com.twilio.chat.demo.*
import com.twilio.chat.demo.views.ChannelViewHolder
import eu.inloop.simplerecycleradapter.ItemClickListener
import eu.inloop.simplerecycleradapter.SettableViewHolder
import eu.inloop.simplerecycleradapter.SimpleRecyclerAdapter
import kotlinx.android.synthetic.main.activity_channel.*
import org.jetbrains.anko.*
import org.json.JSONObject
import org.json.JSONException
import ChatCallbackListener
import ToastStatusListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.twilio.chat.demo.utils.Where.*
import com.twilio.chat.demo.utils.simulateCrash

class ChannelActivity : Activity(), ChatClientListener, AnkoLogger {
    private lateinit var basicClient: BasicChatClient
    private val channels = HashMap<String, ChannelModel>()
    private lateinit var adapter: SimpleRecyclerAdapter<ChannelModel>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel)

        basicClient = TwilioApplication.instance.basicClient
        basicClient.chatClient?.setListener(this@ChannelActivity)
        setupListView()
    }

    override fun onResume() {
        super.onResume()
        getChannels()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.channel, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_create_public -> showCreateChannelDialog(ChannelType.PUBLIC)
            R.id.action_create_private -> showCreateChannelDialog(ChannelType.PRIVATE)
            R.id.action_create_public_withoptions -> createChannelWithType(ChannelType.PUBLIC)
            R.id.action_create_private_withoptions -> createChannelWithType(ChannelType.PRIVATE)
            R.id.action_search_by_unique_name -> showSearchChannelDialog()
            R.id.action_user_info -> startActivity(Intent(applicationContext, UserActivity::class.java))
            R.id.action_update_token -> basicClient.updateToken()
            R.id.action_logout -> {
                basicClient.shutdown()
                finish()
            }
            R.id.action_unregistercm -> basicClient.unregisterFcmToken()
            R.id.action_crash_in_chat_client -> basicClient.chatClient!!.simulateCrash(CHAT_CLIENT_CPP)
            R.id.action_crash_in_tm_client -> basicClient.chatClient!!.simulateCrash(TM_CLIENT_CPP)
        }
        return super.onOptionsItemSelected(item)
    }

    private fun createChannelWithType(type: ChannelType) {
        val rand = Random()
        val value = rand.nextInt(50)

        val attrs = JSONObject()
        try {
            attrs.put("topic", "testing channel creation with options ${value}")
        } catch (xcp: JSONException) {
            error { "JSON exception: $xcp" }
        }

        val typ = if (type == ChannelType.PRIVATE) "Priv" else "Pub"

        val builder = basicClient.chatClient?.channels?.channelBuilder()

        builder?.withFriendlyName("${typ}_TestChannelF_${value}")
               ?.withUniqueName("${typ}_TestChannelU_${value}")
               ?.withType(type)
               ?.withAttributes(attrs)
               ?.build(object : CallbackListener<Channel>() {
                    override fun onSuccess(newChannel: Channel) {
                        debug { "Successfully created a channel with options." }
                        channels.put(newChannel.sid, ChannelModel(newChannel))
                        refreshChannelList()
                    }

                    override fun onError(errorInfo: ErrorInfo?) {
                        error { "Error creating a channel" }
                    }
                })
    }

    private fun showCreateChannelDialog(type: ChannelType) {
        alert(R.string.title_add_channel) {
            customView {
                verticalLayout {
                    textView {
                        text = "Enter ${type} name"
                        padding = dip(10)
                    }.lparams(width = matchParent)
                    val channel_name = editText { padding = dip(10) }.lparams(width = matchParent)
                    positiveButton(R.string.create) {
                        val channelName = channel_name.text.toString()
                        debug { "Creating channel with friendly Name|$channelName|" }
                        basicClient.chatClient?.channels?.createChannel(channelName, type, ChatCallbackListener<Channel>() {
                            debug { "Channel created with sid|${it.sid}| and type ${it.type}" }
                            channels.put(it.sid, ChannelModel(it))
                            refreshChannelList()
                        })
                    }
                    negativeButton(R.string.cancel) {}
                }
            }
        }.show()
    }

    private fun showSearchChannelDialog() {
        alert(R.string.title_find_channel) {
            customView {
                verticalLayout {
                    textView {
                        text = "Enter unique channel name"
                        padding = dip(10)
                    }.lparams(width = matchParent)
                    val channel_name = editText { padding = dip(10) }.lparams(width = matchParent)
                    positiveButton(R.string.search) {
                        val channelSid = channel_name.text.toString()
                        debug { "Searching for ${channelSid}" }
                        basicClient.chatClient?.channels?.getChannel(channelSid, ChatCallbackListener<Channel?>() {
                            if (it != null) {
                                TwilioApplication.instance.showToast("${it.sid}: ${it.friendlyName}")
                            } else {
                                TwilioApplication.instance.showToast("Channel not found.")
                            }
                        })
                    }
                    negativeButton(R.string.cancel) {}
                }
            }
        }.show()
    }

    private fun setupListView() {
        adapter = SimpleRecyclerAdapter(
                ItemClickListener { channel: ChannelModel, _, _ ->
                    if (channel.status == Channel.ChannelStatus.JOINED) {
                        Handler().postDelayed({
                            channel.getChannel(ChatCallbackListener<Channel>() {
                                startActivity<MessageActivity>(
                                    Constants.EXTRA_CHANNEL to it,
                                    Constants.EXTRA_CHANNEL_SID to it.sid
                                )
                            })
                        }, 0)
                        return@ItemClickListener
                    }
                    alert(R.string.select_action) {
                        positiveButton("Join") { dialog ->
                            channel.join(
                                    ToastStatusListener("Successfully joined channel",
                                            "Failed to join channel") {
                                        refreshChannelList()
                                    })
                            dialog.cancel()
                        }
                        negativeButton(R.string.cancel) {}
                    }.show()
                },
                object : SimpleRecyclerAdapter.CreateViewHolder<ChannelModel>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SettableViewHolder<ChannelModel> {
                        return ChannelViewHolder(this@ChannelActivity, parent);
                    }
                })

        channel_list.adapter = adapter
        channel_list.layoutManager = LinearLayoutManager(this).apply {
            orientation = LinearLayoutManager.VERTICAL
        }
    }

    private fun refreshChannelList() {
        adapter.clear()
        adapter.addItems(channels.values.sortedWith(CustomChannelComparator()))
        adapter.notifyDataSetChanged()
    }

    private fun getChannelsPage(paginator: Paginator<ChannelDescriptor>) {
        for (cd in paginator.items) {
            error { "Adding channel descriptor for sid|${cd.sid}| friendlyName ${cd.friendlyName}" }
            channels.put(cd.sid, ChannelModel(cd))
        }
        refreshChannelList()

        if (paginator.hasNextPage()) {
            paginator.requestNextPage(object : CallbackListener<Paginator<ChannelDescriptor>>() {
                override fun onSuccess(channelDescriptorPaginator: Paginator<ChannelDescriptor>) {
                    getChannelsPage(channelDescriptorPaginator)
                }
            })
        } else {
            // Get subscribed channels last - so their status will overwrite whatever we received
            // from public list. Ugly workaround for now.
            val chans = basicClient.chatClient?.channels?.subscribedChannels
            if (chans != null) {
                for (channel in chans) {
                    channels.put(channel.sid, ChannelModel(channel))
                }
            }
            refreshChannelList()
        }
    }

    // Initialize channels with channel list
    private fun getChannels() {
        channels.clear()

        basicClient.chatClient?.channels?.getPublicChannelsList(object : CallbackListener<Paginator<ChannelDescriptor>>() {
            override fun onSuccess(channelDescriptorPaginator: Paginator<ChannelDescriptor>) {
                getChannelsPage(channelDescriptorPaginator)
            }
        })

        basicClient.chatClient?.channels?.getUserChannelsList(object : CallbackListener<Paginator<ChannelDescriptor>>() {
            override fun onSuccess(channelDescriptorPaginator: Paginator<ChannelDescriptor>) {
                getChannelsPage(channelDescriptorPaginator)
            }
        })
    }

    private fun showIncomingInvite(channel: Channel) {
        alert(R.string.channel_invite_message, R.string.channel_invite) {
            customView {
                verticalLayout {
                    textView {
                        text = "You are invited to channel ${channel.friendlyName} |${channel.sid}|"
                        padding = dip(10)
                    }.lparams(width = matchParent)
                    positiveButton(R.string.join) {
                        channel.join(ToastStatusListener(
                                "Successfully joined channel",
                                "Failed to join channel") {
                            channels.put(channel.sid, ChannelModel(channel))
                            refreshChannelList()
                        })
                    }
                    negativeButton(R.string.decline) {
                        channel.declineInvitation(ToastStatusListener(
                                "Successfully declined channel invite",
                                "Failed to decline channel invite"))
                    }
                }
            }
        }.show()
    }

    private inner class CustomChannelComparator : Comparator<ChannelModel> {
        override fun compare(lhs: ChannelModel, rhs: ChannelModel): Int {
            return lhs.friendlyName.compareTo(rhs.friendlyName)
        }
    }

    //=============================================================
    // ChatClientListener
    //=============================================================

    override fun onChannelJoined(channel: Channel) {
        debug { "Received onChannelJoined callback for channel |${channel.friendlyName}|" }
        channels.put(channel.sid, ChannelModel(channel))
        refreshChannelList()
    }

    override fun onChannelAdded(channel: Channel) {
        debug { "Received onChannelAdded callback for channel |${channel.friendlyName}|" }
        channels.put(channel.sid, ChannelModel(channel))
        refreshChannelList()
    }

    override fun onChannelUpdated(channel: Channel, reason: Channel.UpdateReason) {
        debug { "Received onChannelUpdated callback for channel |${channel.friendlyName}| with reason ${reason}" }
        channels.put(channel.sid, ChannelModel(channel))
        refreshChannelList()
    }

    override fun onChannelDeleted(channel: Channel) {
        debug { "Received onChannelDeleted callback for channel |${channel.friendlyName}|" }
        channels.remove(channel.sid)
        refreshChannelList()
    }

    override fun onChannelInvited(channel: Channel) {
        channels.put(channel.sid, ChannelModel(channel))
        refreshChannelList()
        showIncomingInvite(channel)
    }

    override fun onChannelSynchronizationChange(channel: Channel) {
        error { "Received onChannelSynchronizationChange callback for channel |${channel.friendlyName}| with new status ${channel.status}" }
        refreshChannelList()
    }

    override fun onClientSynchronization(status: ChatClient.SynchronizationStatus) {
        error { "Received onClientSynchronization callback ${status}" }
    }

    override fun onUserUpdated(user: User, reason: User.UpdateReason) {
        error { "Received onUserUpdated callback for ${reason}" }
    }

    override fun onUserSubscribed(user: User) {
        error { "Received onUserSubscribed callback" }
    }

    override fun onUserUnsubscribed(user: User) {
        error { "Received onUserUnsubscribed callback" }
    }

    override fun onNewMessageNotification(channelSid: String?, messageSid: String?, messageIndex: Long) {
        TwilioApplication.instance.showToast("Received onNewMessage push notification")
    }

    override fun onAddedToChannelNotification(channelSid: String?) {
        TwilioApplication.instance.showToast("Received onAddedToChannel push notification")
    }

    override fun onInvitedToChannelNotification(channelSid: String?) {
        TwilioApplication.instance.showToast("Received onInvitedToChannel push notification")
    }

    override fun onRemovedFromChannelNotification(channelSid: String?) {
        TwilioApplication.instance.showToast("Received onRemovedFromChannel push notification")
    }

    override fun onNotificationSubscribed() {
        TwilioApplication.instance.showToast("Subscribed to push notifications")
    }

    override fun onNotificationFailed(errorInfo: ErrorInfo) {
        TwilioApplication.instance.showError("Failed to subscribe to push notifications", errorInfo)
    }

    override fun onError(errorInfo: ErrorInfo) {
        TwilioApplication.instance.showError("Received error", errorInfo)
    }

    override fun onConnectionStateChange(connectionState: ChatClient.ConnectionState) {
        TwilioApplication.instance.showToast("Transport state changed to ${connectionState}")
    }

    override fun onTokenExpired() {
        basicClient.onTokenExpired()
    }

    override fun onTokenAboutToExpire() {
        basicClient.onTokenAboutToExpire()
    }

    companion object {
        private val CHANNEL_OPTIONS = arrayOf("Join")
        private val JOIN = 0
    }
}
