package me.saket.dank.notifs;

import static java.util.Collections.unmodifiableSet;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.support.annotation.CheckResult;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.Action;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.RemoteInput;
import android.support.v4.content.ContextCompat;
import android.text.Html;

import net.dean.jraw.models.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.Completable;
import io.reactivex.Single;
import me.saket.dank.R;
import me.saket.dank.data.InboxManager;
import me.saket.dank.di.Dank;
import me.saket.dank.utils.JrawUtils;
import me.saket.dank.utils.Markdown;
import me.saket.dank.utils.Strings;
import timber.log.Timber;

public class MessagesNotificationManager {

  private static final int P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_SEEN = 200;
  private static final int P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_READ = 201;
  private static final int P_INTENT_REQ_ID_MARK_AS_READ = 202;
  private static final int P_INTENT_REQ_ID_MARK_AS_SEEN = 203;
  private static final int P_INTENT_REQ_ID_DIRECT_REPLY = 204;
  private static final int P_INTENT_REQ_ID_OPEN_INBOX = 205;

  private final SeenUnreadMessageIdStore seenMessageIdsStore;

  public MessagesNotificationManager(SeenUnreadMessageIdStore seenMessageIdsStore) {
    this.seenMessageIdsStore = seenMessageIdsStore;
  }

  /**
   * Remove messages whose notifications the user has already seen (by dismissing it).
   */
  @CheckResult
  public Single<List<Message>> filterUnseenMessages(List<Message> unfilteredMessages) {
    return seenMessageIdsStore.get()
        .map(seenMessageIds -> {
          List<Message> unseenMessages = new ArrayList<>(unfilteredMessages.size());

          for (Message unfilteredMessage : unfilteredMessages) {
            if (!seenMessageIds.contains(unfilteredMessage.getId())) {
              unseenMessages.add(unfilteredMessage);
            } else {
              Timber.w("Already seen: %s", Strings.safeSubstring(unfilteredMessage.getBody(), 50));
            }
          }

          return Collections.unmodifiableList(unseenMessages);
        });
  }

  @CheckResult
  public Completable markMessageNotifAsSeen(List<String> messageIds) {
    return seenMessageIdsStore.get()
        .flatMapCompletable(existingSeenMessageIds -> {
          Set<String> updatedSeenMessages = new HashSet<>(existingSeenMessageIds.size() + messageIds.size());
          updatedSeenMessages.addAll(existingSeenMessageIds);
          updatedSeenMessages.addAll(messageIds);
          return seenMessageIdsStore.save(updatedSeenMessages);
        });
  }

  @CheckResult
  public Completable markMessageNotifAsSeen(Message... messages) {
    List<String> messageIds = new ArrayList<>(messages.length);
    for (Message message : messages) {
      messageIds.add(message.getId());
    }
    return markMessageNotifAsSeen(messageIds);
  }

  /**
   * Recycle <var>message</var>'s ID when it's no longer unread.
   */
  @CheckResult
  public Completable removeMessageNotifSeenStatus(Message... messages) {
    return seenMessageIdsStore.get()
        .map(oldSeenMessageIds -> {
          Set<String> updatedSeenMessageIds = new HashSet<>(oldSeenMessageIds.size());
          updatedSeenMessageIds.addAll(oldSeenMessageIds);
          Timber.d("---------------------");
          for (Message message : messages) {
            Timber.i("Removing seen for: %s", Strings.safeSubstring(message.getBody(), 50));
            updatedSeenMessageIds.remove(message.getId());
          }
          return Collections.unmodifiableSet(updatedSeenMessageIds);
        })
        .toCompletable();
  }

  /**
   * Empty the seen message Ids when there are no more unread messages present.
   */
  @CheckResult
  public Completable removeAllMessageNotifSeenStatuses() {
    return seenMessageIdsStore.save(Collections.emptySet());
  }

  public static class SeenUnreadMessageIdStore {
    private final SharedPreferences sharedPreferences;

    private static final String KEY_SEEN_UNREAD_MESSAGES = "seenUnreadMessages";

    public SeenUnreadMessageIdStore(SharedPreferences sharedPreferences) {
      this.sharedPreferences = sharedPreferences;
    }

    /**
     * @param seenMessageIds IDs of unread messages whose notifications the user has already seen.
     */
    @CheckResult
    public Completable save(Set<String> seenMessageIds) {
      return Completable.fromAction(() -> sharedPreferences.edit().putStringSet(KEY_SEEN_UNREAD_MESSAGES, seenMessageIds).apply());
    }

    /**
     * @return Message IDs that the user has already seen.
     */
    @CheckResult
    public Single<Set<String>> get() {
      return Single.fromCallable(() -> {
        Set<String> seenMessageIdSet = sharedPreferences.getStringSet(KEY_SEEN_UNREAD_MESSAGES, Collections.emptySet());
        return unmodifiableSet(seenMessageIdSet);
      });
    }
  }

// ======== NOTIFICATION ======== //

  public Completable displayNotification(Context context, List<Message> unreadMessages) {
    return Completable.fromAction(() -> {
      String loggedInUserName = Dank.userSession().loggedInUserName();

      Comparator<Message> oldestMessageFirstComparator = (first, second) -> {
        Date firstDate = first.getCreated();
        Date secondDate = second.getCreated();

        if (firstDate.after(secondDate)) {
          return -1;
        } else if (secondDate.after(firstDate)) {
          return +1;
        } else {
          return 0;   // Equal
        }
      };
      List<Message> sortedMessages = new ArrayList<>(unreadMessages.size());
      sortedMessages.addAll(unreadMessages);
      Collections.sort(sortedMessages, oldestMessageFirstComparator);

      Timber.i("Creating notifs for:");
      for (Message sortedMessage : sortedMessages) {
        Timber.i("%s (%s)", Strings.safeSubstring(sortedMessage.getBody(), 50), sortedMessage.getCreated());
      }
      createNotifications(context, Collections.unmodifiableList(sortedMessages), loggedInUserName);
    });
  }

  /**
   * Constructs bundled notifications for unread messages.
   */
  private void createNotifications(Context context, List<Message> unreadMessages, String loggedInUserName) {
    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

    // This summary notification will only be used on < Nougat, where bundled notifications aren't supported.
    // Though, Android will still pick up some properties from it on Nougat, like the sound, vibration, icon, etc.
    // The style (InboxStyle, MessagingStyle) is dropped on Nougat.
    NotificationCompat.Builder summaryNotifBuilder = unreadMessages.size() == 1
        ? createSingleMessageSummaryNotifBuilder(context, unreadMessages.get(0), loggedInUserName)
        : createMultipleMessagesSummaryNotifBuilder(context, unreadMessages, loggedInUserName);

    // Open Inbox on click.
    PendingIntent onSummaryClickPendingIntent = PendingIntent.getBroadcast(
        context,
        P_INTENT_REQ_ID_OPEN_INBOX,
        MessageNotifActionReceiver.createMarkAllSeenAndOpenInboxIntent(context, unreadMessages),
        PendingIntent.FLAG_UPDATE_CURRENT
    );

    Notification summaryNotification = summaryNotifBuilder
        .setGroup(NotificationConstants.UNREAD_MESSAGE_BUNDLE_NOTIFS_GROUP_KEY)
        .setGroupSummary(true)
        .setShowWhen(true)
        .setColor(ContextCompat.getColor(context, R.color.notification_icon_color))
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setDefaults(Notification.DEFAULT_ALL)
        .setOnlyAlertOnce(true)
        .setContentIntent(onSummaryClickPendingIntent)
        .setAutoCancel(true)
        .setChannelId(context.getString(R.string.notification_channel_unread_messages_id))
        .build();
    notificationManager.notify(NotificationConstants.ID_UNREAD_MESSAGES_BUNDLE_SUMMARY, summaryNotification);

    // Add bundled notifications (Nougat+).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      Timber.i("%s bundled notifs", unreadMessages.size());

      for (Message unreadMessage : unreadMessages) {
        int notificationId = createNotificationIdFor(unreadMessage);

        // Mark as read action.
        PendingIntent markAsReadPendingIntent = createMarkAsReadPendingIntent(context, unreadMessage, P_INTENT_REQ_ID_MARK_AS_READ + notificationId);
        Action markAsReadAction = new Action.Builder(0, context.getString(R.string.messagenotification_mark_as_read), markAsReadPendingIntent).build();

        // Direct reply action.
        Intent directReplyIntent = MessageNotifActionReceiver.createDirectReplyIntent(context, unreadMessage, Dank.moshi(), notificationId);
        PendingIntent directReplyPendingIntent = PendingIntent.getBroadcast(
            context,
            P_INTENT_REQ_ID_DIRECT_REPLY + notificationId,
            directReplyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT
        );
        Action replyAction = new Action.Builder(0, context.getString(R.string.messagenotification_reply), directReplyPendingIntent)
            .addRemoteInput(new RemoteInput.Builder(MessageNotifActionReceiver.KEY_DIRECT_REPLY_MESSAGE)
                .setLabel(context.getString(R.string.messagenotification_reply_to_user, unreadMessage.getAuthor()))
                .build())
            .setAllowGeneratedReplies(true)
            .build();

        // Mark as seen on dismissal.
        PendingIntent deletePendingIntent = createMarkAsSeenPendingIntent(context, unreadMessage, P_INTENT_REQ_ID_MARK_AS_SEEN + notificationId);

        // Open Inbox on click.
        PendingIntent onClickPendingIntent = PendingIntent.getBroadcast(
            context,
            P_INTENT_REQ_ID_OPEN_INBOX,
            MessageNotifActionReceiver.createMarkAsSeenAndOpenInboxIntent(context, unreadMessage),
            PendingIntent.FLAG_UPDATE_CURRENT
        );

        String markdownStrippedBody = Markdown.stripMarkdown(JrawUtils.messageBodyHtml(unreadMessage));

        Notification bundledNotification = new NotificationCompat.Builder(context)
            .setContentTitle(unreadMessage.getAuthor())
            .setContentText(markdownStrippedBody)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(markdownStrippedBody))
            .setShowWhen(true)
            .setWhen(JrawUtils.createdTimeUtc(unreadMessage))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setGroup(NotificationConstants.UNREAD_MESSAGE_BUNDLE_NOTIFS_GROUP_KEY)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.color_accent))
            .addAction(markAsReadAction)
            .addAction(replyAction)
            .setContentIntent(onClickPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setChannelId(context.getString(R.string.notification_channel_unread_messages_id))
            .build();
        notificationManager.notify(notificationId, bundledNotification);
      }
    }
  }

  /**
   * Create an "BigTextStyle" notification for a single unread messages.
   */
  private NotificationCompat.Builder createSingleMessageSummaryNotifBuilder(Context context, Message unreadMessage, String loggedInUserName) {
    // Mark as read action.
    PendingIntent markAsReadPendingIntent = createMarkAsReadPendingIntent(context, unreadMessage, P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_READ);
    Action markAsReadAction = new Action.Builder(R.drawable.ic_done_24dp, context.getString(R.string.messagenotification_mark_as_read), markAsReadPendingIntent).build();

    // Dismissal intent.
    PendingIntent deletePendingIntent = createMarkAsSeenPendingIntent(context, unreadMessage, P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_SEEN);

    // Update: Lol using some tags crashes Android's SystemUi. We'll have to remove all markdown tags.
    String markdownStrippedBody = Markdown.stripMarkdown(JrawUtils.messageBodyHtml(unreadMessage));

    return new NotificationCompat.Builder(context)
        .setContentTitle(unreadMessage.getAuthor())
        .setContentText(markdownStrippedBody)
        .setStyle(new NotificationCompat.BigTextStyle()
            .bigText(markdownStrippedBody)
            .setSummaryText(loggedInUserName))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setDeleteIntent(deletePendingIntent)
        .addAction(markAsReadAction);
  }

  /**
   * Create an "InboxStyle" notification for multiple unread messages.
   */
  private NotificationCompat.Builder createMultipleMessagesSummaryNotifBuilder(Context context, List<Message> unreadMessages,
      String loggedInUserName)
  {
    // Create a "InboxStyle" summary notification for the bundled notifs, that will only be visible on < Nougat.
    NotificationCompat.InboxStyle messagingStyleBuilder = new NotificationCompat.InboxStyle();
    messagingStyleBuilder.setSummaryText(loggedInUserName);
    int linesAdded = 0;

    for (Message unreadMessage : unreadMessages) {
      CharSequence messageBodyWithMarkdown = Markdown.stripMarkdown(JrawUtils.messageBodyHtml(unreadMessage));
      String markdownStrippedBody = messageBodyWithMarkdown.toString();
      //noinspection deprecation
      messagingStyleBuilder.addLine(Html.fromHtml(context.getString(
          R.string.messagenotification_below_nougat_expanded_body_row,
          unreadMessage.getAuthor(),
          markdownStrippedBody
      )));

      // Bug workaround: Android displays a random integer after the 7th item. E.g., "@17041057.
      // Manually limit the lines to 7 to avoid this.
      if (++linesAdded == 7) {
        break;
      }
    }

    // Mark all as seen on summary notif dismissal.
    Intent markAllAsSeenIntent = MessageNotifActionReceiver.createMarkAllAsSeenIntent(context, unreadMessages);
    PendingIntent summaryDeletePendingIntent = PendingIntent.getBroadcast(
        context,
        P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_SEEN,
        markAllAsSeenIntent,
        PendingIntent.FLAG_UPDATE_CURRENT
    );

    // Mark all as read action. Will only show up on < Nougat.
    Intent markAllAsReadIntent = MessageNotifActionReceiver.createMarkAllAsReadIntent(context, unreadMessages);
    PendingIntent markAllAsReadPendingIntent = PendingIntent.getBroadcast(
        context,
        P_INTENT_REQ_ID_SUMMARY_MARK_ALL_AS_READ,
        markAllAsReadIntent,
        PendingIntent.FLAG_UPDATE_CURRENT
    );
    Action markAllReadAction = new Action.Builder(R.drawable.ic_done_all_24dp, context.getString(R.string.messagenotification_mark_all_as_read), markAllAsReadPendingIntent).build();

    // Since message API is paginated, it's possible that the user has multiple pages of unread messages
    // and we could only fetch the first page.
    String notificationTitle = unreadMessages.size() == InboxManager.MESSAGES_FETCHED_PER_PAGE
        ? context.getString(R.string.messagenotification_below_nougat_multiple_messages_title_indeterminate, unreadMessages.size())
        : context.getString(R.string.messagenotification_below_nougat_multiple_messages_title, unreadMessages.size());

    // Notification body.
    // Using a Set to remove duplicate author names.
    Set<String> messageAuthors = new LinkedHashSet<>(unreadMessages.size());
    for (Message unreadMessage : unreadMessages) {
      messageAuthors.add(unreadMessage.getAuthor());
    }
    String notifBody = messageAuthors.size() == 1
        ? context.getString(R.string.messagenotification_below_nougat_body_from_single_author, messageAuthors.iterator().next())
        : Strings.concatenateWithCommaAndAnd(context.getResources(), messageAuthors);

    return new NotificationCompat.Builder(context)
        .setContentTitle(notificationTitle)
        .setContentText(notifBody)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setStyle(messagingStyleBuilder)
        .setDeleteIntent(summaryDeletePendingIntent)
        .addAction(markAllReadAction);
  }

  private PendingIntent createMarkAsReadPendingIntent(Context context, Message unreadMessage, int requestId) {
    Intent markAsReadIntent = MessageNotifActionReceiver.createMarkAsReadIntent(context, Dank.moshi(), unreadMessage);
    return PendingIntent.getBroadcast(context, requestId, markAsReadIntent, PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private PendingIntent createMarkAsSeenPendingIntent(Context context, Message unreadMessage, int requestId) {
    Intent markAsSeenIntent = MessageNotifActionReceiver.createMarkAsSeenIntent(context, unreadMessage);
    return PendingIntent.getBroadcast(context, requestId, markAsSeenIntent, PendingIntent.FLAG_UPDATE_CURRENT);
  }

  /**
   * Id used for generating a notification for <var>message</var>. Used for dismissing it if it's active.
   */
  public static int createNotificationIdFor(Message message) {
    return (NotificationConstants.UNREAD_MESSAGE_PREFIX_ + message.getId()).hashCode();
  }

  @CheckResult
  public Completable dismissNotification(Context context, Message... messages) {
    return Completable.fromAction(() -> {
      NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
      for (Message message : messages) {
        int notificationId = createNotificationIdFor(message);
        Timber.i("dismissNotification %s", notificationId);
        if (notificationId == -1) {
          throw new IllegalStateException();
        }
        notificationManager.cancel(notificationId);
      }
    });
  }

  /**
   * Dismiss the summary notification of a bundle so that everything gets dismissed.
   */
  @CheckResult
  public Completable dismissAllNotifications(Context context) {
    return Completable.fromAction(() -> {
      //Timber.i("Dismissing all notifs");
      NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
      notificationManager.cancel(NotificationConstants.ID_UNREAD_MESSAGES_BUNDLE_SUMMARY);
    });
  }
}
