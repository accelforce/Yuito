package net.accelf.yuito;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.keylesspalace.tusky.ComposeActivity;
import com.keylesspalace.tusky.R;
import com.keylesspalace.tusky.appstore.Event;
import com.keylesspalace.tusky.appstore.EventHub;
import com.keylesspalace.tusky.appstore.PreferenceChangedEvent;
import com.keylesspalace.tusky.appstore.QuickReplyEvent;
import com.keylesspalace.tusky.db.AccountEntity;
import com.keylesspalace.tusky.db.AccountManager;
import com.keylesspalace.tusky.entity.Status;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class QuickTootHelper {

    private Context context;
    private TextView quickReplyInfo;
    private ImageView visibilityButton;
    private EditText tootEditText;

    private SharedPreferences defPrefs;
    private String domain;
    private String loggedInUsername;
    private EventHub eventHub;

    private Status inReplyTo;

    private static final String PREF_CURRENT_VISIBILITY = "current_visibility";

    public QuickTootHelper(ConstraintLayout root, SharedPreferences defPrefs, AccountManager accountManager, EventHub eventHub) {
        context = root.getContext();
        quickReplyInfo = root.findViewById(R.id.quick_reply_info);
        visibilityButton = root.findViewById(R.id.visibility_button);
        tootEditText = root.findViewById(R.id.toot_edit_text);
        Button quickTootButton = root.findViewById(R.id.toot_button);

        this.defPrefs = defPrefs;
        AccountEntity account = accountManager.getActiveAccount();
        if (account != null) {
            domain = account.getDomain();
            loggedInUsername = account.getUsername();
        }

        this.eventHub = eventHub;

        updateVisibilityButton();
        visibilityButton.setOnClickListener(v -> setNextVisibility());
        quickTootButton.setOnClickListener(v -> quickToot());
    }

    public void composeButton() {
        if (tootEditText.getText().length() == 0 && inReplyTo == null) {
            Intent composeIntent = new Intent(context, ComposeActivity.class);
            context.startActivity(composeIntent);
        } else {
            startComposeWithQuickComposeData();
        }
    }

    public void handleEvent(Event event) {
        if (event instanceof QuickReplyEvent) {
            reply(((QuickReplyEvent) event).getStatus());
        } else if (event instanceof PreferenceChangedEvent) {
            if (PREF_CURRENT_VISIBILITY.equals(((PreferenceChangedEvent) event).getPreferenceKey())) {
                updateVisibilityButton();
            }
        }
    }

    private void reply(Status status) {
        inReplyTo = status;
        updateQuickReplyInfo();
    }

    private void startComposeWithQuickComposeData() {
        Intent composeIntent = setupIntentBuilder(false);
        resetQuickCompose();
        context.startActivity(composeIntent);
    }

    private void quickToot() {
        if (tootEditText.getText().toString().length() > 0) {
            Intent composeIntent = setupIntentBuilder(true);
            resetQuickCompose();
            context.startActivity(composeIntent);
        }
    }

    private Intent setupIntentBuilder(boolean tootRightNow) {
        ComposeActivity.IntentBuilder intentBuilder = new ComposeActivity.IntentBuilder()
                .tootText(tootEditText.getText().toString())
                .visibility(getCurrentVisibility())
                .tootRightNow(tootRightNow);

        if (inReplyTo == null) {
            return intentBuilder.build(context);
        }

        Status.Mention[] mentions = inReplyTo.getMentions();
        Set<String> mentionedUsernames = new LinkedHashSet<>();
        mentionedUsernames.add(inReplyTo.getAccount().getUsername());
        for (Status.Mention mention : mentions) {
            mentionedUsernames.add(mention.getUsername());
        }
        mentionedUsernames.remove(loggedInUsername);

        return intentBuilder.inReplyToId(inReplyTo.getId())
                .contentWarning(inReplyTo.getSpoilerText())
                .mentionedUsernames(mentionedUsernames)
                .replyingStatusAuthor(inReplyTo.getAccount().getLocalUsername())
                .replyingStatusContent(inReplyTo.getContent().toString())
                .build(context);
    }

    private void resetQuickCompose() {
        tootEditText.getText().clear();
        inReplyTo = null;
        updateQuickReplyInfo();
    }

    private void updateQuickReplyInfo() {
        if (inReplyTo != null) {
            quickReplyInfo.setText(String.format("Reply to : %s", inReplyTo.getAccount().getUsername()));
        } else {
            quickReplyInfo.setText("");
        }
    }

    private Status.Visibility getCurrentVisibility() {
        Status.Visibility visibility = Status.Visibility.byNum(defPrefs.getInt(PREF_CURRENT_VISIBILITY, Status.Visibility.PUBLIC.getNum()));
        if (!Arrays.asList(ComposeActivity.CAN_USE_UNLEAKABLE)
                .contains(domain) && visibility == Status.Visibility.UNLEAKABLE) {
            defPrefs.edit()
                    .putInt(PREF_CURRENT_VISIBILITY, Status.Visibility.PUBLIC.getNum())
                    .apply();
            eventHub.dispatch(new PreferenceChangedEvent(PREF_CURRENT_VISIBILITY));
            return Status.Visibility.PUBLIC;
        }
        return visibility;
    }

    private void updateVisibilityButton() {
        Status.Visibility visibility = getCurrentVisibility();
        switch (visibility) {
            case PUBLIC:
                visibilityButton.setImageResource(R.drawable.ic_public_24dp);
                break;
            case UNLISTED:
                visibilityButton.setImageResource(R.drawable.ic_lock_open_24dp);
                break;
            case PRIVATE:
                visibilityButton.setImageResource(R.drawable.ic_lock_outline_24dp);
                break;
            case UNLEAKABLE:
                visibilityButton.setImageResource(R.drawable.ic_unleakable_24dp);
                break;
        }
    }

    private void setNextVisibility() {
        Status.Visibility visibility = getCurrentVisibility();
        switch (visibility) {
            case PUBLIC:
                visibility = Status.Visibility.UNLISTED;
                break;
            case UNLISTED:
                visibility = Status.Visibility.PRIVATE;
                break;
            case PRIVATE:
                if (Arrays.asList(ComposeActivity.CAN_USE_UNLEAKABLE)
                        .contains(domain)) {
                    visibility = Status.Visibility.UNLEAKABLE;
                } else {
                    visibility = Status.Visibility.PUBLIC;
                }
                break;
            case UNLEAKABLE:
            case UNKNOWN:
                visibility = Status.Visibility.PUBLIC;
                break;
        }
        defPrefs.edit()
                .putInt(PREF_CURRENT_VISIBILITY, visibility.getNum())
                .apply();
        eventHub.dispatch(new PreferenceChangedEvent(PREF_CURRENT_VISIBILITY));
        updateVisibilityButton();
    }
}
