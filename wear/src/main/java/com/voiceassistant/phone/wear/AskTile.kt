package com.voiceassistant.phone.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Colors
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.ListenableFuture

/**
 * The tile: swipe to it from the watch face and tap once to ask. It opens the app already listening ([MainActivity.EXTRA_TALK]), so a question
 * takes one swipe and one tap. The tile itself shows nothing from the assistant; everything still goes through the phone.
 */
class AskTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val open = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(MainActivity.EXTRA_TALK, ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build())
                    .build(),
            ).build()
        val click = ModifiersBuilders.Clickable.Builder().setId("ask").setOnClick(open).build()
        val button = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(96f)).setHeight(dp(96f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(click)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(Colors.DEFAULT.primary))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(48f)).build())
                            .build(),
                    ).build(),
            )
            .addContent(
                LayoutElementBuilders.Text.Builder().setText("Ask")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(androidx.wear.protolayout.DimensionBuilders.sp(22f)).setColor(argb(Colors.DEFAULT.onPrimary)).build())
                    .build(),
            ).build()
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(button).build()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return CallbackToFutureAdapter.getFuture { completer -> completer.set(tile); "ask tile" }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer -> completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()); "ask tile resources" }

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}
