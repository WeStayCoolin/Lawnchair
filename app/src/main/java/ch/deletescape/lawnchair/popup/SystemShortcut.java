package ch.deletescape.lawnchair.popup;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;

import java.util.List;

import ch.deletescape.lawnchair.AbstractFloatingView;
import ch.deletescape.lawnchair.EditAppDialog;
import ch.deletescape.lawnchair.EditableItemInfo;
import ch.deletescape.lawnchair.InfoDropTarget;
import ch.deletescape.lawnchair.ItemInfo;
import ch.deletescape.lawnchair.Launcher;
import ch.deletescape.lawnchair.R;
import ch.deletescape.lawnchair.model.WidgetItem;
import ch.deletescape.lawnchair.util.PackageUserKey;
import ch.deletescape.lawnchair.util.Themes;
import ch.deletescape.lawnchair.widget.WidgetsBottomSheet;

public abstract class SystemShortcut {
    private final int mIconResId;
    private final int mLabelResId;

    public SystemShortcut(int iconResId, int labelResId) {
        mIconResId = iconResId;
        mLabelResId = labelResId;
    }

    public Drawable getIcon(Context context) {
        return context.getResources().getDrawable(mIconResId, context.getTheme());
    }

    public String getLabel(Context context) {
        return context.getString(mLabelResId);
    }

    public abstract View.OnClickListener getOnClickListener(final Launcher launcher,
                                                            final ItemInfo itemInfo);

    public static class Widgets extends SystemShortcut {

        public Widgets() {
            super(R.drawable.ic_widget, R.string.widget_button_text);
        }

        @Override
        public View.OnClickListener getOnClickListener(final Launcher launcher,
                                                       final ItemInfo itemInfo) {
            final List<WidgetItem> widgets = launcher.getWidgetsForPackageUser(new PackageUserKey(
                    itemInfo.getTargetComponent().getPackageName(), itemInfo.user));
            if (widgets == null) {
                return null;
            }
            return new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    AbstractFloatingView.closeAllOpenViews(launcher);
                    WidgetsBottomSheet widgetsBottomSheet =
                            (WidgetsBottomSheet) launcher.getLayoutInflater().inflate(
                                    R.layout.widgets_bottom_sheet, launcher.getDragLayer(), false);
                    widgetsBottomSheet.populateAndShow(itemInfo);
                }
            };
        }
    }

    public static class AppInfo extends SystemShortcut {
        public AppInfo() {
            super(R.drawable.ic_info_no_shadow, R.string.app_info_drop_target_label);
        }

        @Override
        public View.OnClickListener getOnClickListener(final Launcher launcher,
                                                       final ItemInfo itemInfo) {
            return new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Rect sourceBounds = launcher.getViewBounds(view);
                    Bundle opts = launcher.getActivityLaunchOptions(view);
                    InfoDropTarget.startDetailsActivityForInfo(itemInfo, launcher, null, sourceBounds, opts);
                }
            };
        }
    }
    public static class Edit extends SystemShortcut {
        public Edit() {
            super(R.drawable.ic_edit_no_shadow, R.string.edit_drop_target_label);
        }

        @Override
        public View.OnClickListener getOnClickListener(final Launcher launcher,
                                                       final ItemInfo itemInfo) {
            return new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    AbstractFloatingView.closeAllOpenViews(launcher);
                    launcher.openDialog(new EditAppDialog(launcher, (EditableItemInfo) itemInfo, launcher));
                }
            };
        }
    }
}