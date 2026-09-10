package com.valerochka1337.valerochkagym.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Database-level invariants omitted from Room's schema model and therefore installed on open. */
object CalendarEventAccountLinkSchema {
  fun install(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS calendar_event_account_links_validate_insert
        BEFORE INSERT ON calendar_event_account_links
        WHEN NOT (
          (NEW.state = 'OWNED' AND NEW.ownerEmail IS NOT NULL
            AND length(trim(NEW.ownerEmail)) > 0
            AND NEW.ownerEmail = lower(trim(NEW.ownerEmail)))
          OR (NEW.state = 'LEGACY_OWNER_UNKNOWN' AND NEW.ownerEmail IS NULL)
        )
        BEGIN
          SELECT RAISE(ABORT, 'invalid calendar event owner link');
        END
        """
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS calendar_event_account_links_immutable
        BEFORE UPDATE ON calendar_event_account_links
        BEGIN
          SELECT RAISE(ABORT, 'calendar event owner link is immutable');
        END
        """
    )
  }
}
