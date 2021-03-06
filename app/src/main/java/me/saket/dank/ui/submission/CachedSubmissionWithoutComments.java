package me.saket.dank.ui.submission;

import com.google.auto.value.AutoValue;

/**
 * Cache for submission without comments. Enough to be displayed on the submission list screen.
 */
@AutoValue
@Deprecated
public abstract class CachedSubmissionWithoutComments {

  public static final String TABLE_NAME = "CachedSubmissionWithoutComments";
  public static final String COLUMN_SUBMISSION_FULL_NAME = "submission_full_name";
  public static final String COLUMN_SUBMISSION_JSON = "submission_json";
  private static final String COLUMN_SUBREDDIT_NAME = "subreddit_name";
  private static final String COLUMN_SAVE_TIME = "save_time";
//
//  public static final String QUERY_CREATE_TABLE =
//      "CREATE TABLE " + TABLE_NAME + " ("
//          + COLUMN_SUBMISSION_FULL_NAME + " TEXT NOT NULL PRIMARY KEY, "
//          + COLUMN_SUBMISSION_JSON + " TEXT NOT NULL, "
//          + COLUMN_SUBREDDIT_NAME + " TEXT NOT NULL, "
//          + COLUMN_SAVE_TIME + " INTEGER NOT NULL"
//          + ")";
//
//  public static final String WHERE_SUBREDDIT_NAME = COLUMN_SUBREDDIT_NAME + " == ?";
//
//  public static final String WHERE_SAVE_TIME_BEFORE = COLUMN_SAVE_TIME + " < ?";
//
//  public static final String SELECT_BY_FULLNAME_AND_SAVE_TIME_NEWER_THAN
//      = "SELECT * FROM " + TABLE_NAME
//      + " WHERE " + COLUMN_SUBMISSION_FULL_NAME + " == ? AND " + COLUMN_SAVE_TIME + " > ?";
//
//  public abstract String submissionFullName();
//
//  public abstract Submission submission();
//
//  public abstract String subredditName();
//
//  public abstract long saveTimeMillis();
//
//  public ContentValues toContentValues(JsonAdapter<Submission> submissionJsonAdapter) {
//    ContentValues values = new ContentValues(4);
//    values.put(COLUMN_SUBMISSION_FULL_NAME, submissionFullName());
//    values.put(COLUMN_SUBMISSION_JSON, submissionJsonAdapter.toJson(submission()));
//    values.put(COLUMN_SUBREDDIT_NAME, subredditName());
//    values.put(COLUMN_SAVE_TIME, saveTimeMillis());
//    return values;
//  }
//
//  public static CachedSubmissionWithoutComments create(
//      String submissionFullName,
//      Submission submissionWithoutComments,
//      String subredditName,
//      long saveTimeMillis)
//  {
//    return new AutoValue_CachedSubmissionWithoutComments(submissionFullName, submissionWithoutComments, subredditName, saveTimeMillis);
//  }
//
//  public static CachedSubmissionWithoutComments createFromCursor(Cursor cursor, Moshi moshi) throws IOException {
//    String submissionFullName = Cursors.string(cursor, COLUMN_SUBMISSION_FULL_NAME);
//    String submissionJson = Cursors.string(cursor, COLUMN_SUBMISSION_JSON);
//    Submission submission = moshi.adapter(Submission.class).fromJson(submissionJson);
//    String subredditName = Cursors.string(cursor, COLUMN_SUBREDDIT_NAME);
//    long saveTimeMillis = Cursors.longg(cursor, COLUMN_SAVE_TIME);
//    //noinspection ConstantConditions
//    return create(submissionFullName, submission, subredditName, saveTimeMillis);
//  }
}
