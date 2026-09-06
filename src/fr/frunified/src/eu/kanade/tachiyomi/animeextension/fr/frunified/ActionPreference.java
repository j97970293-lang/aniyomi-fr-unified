package eu.kanade.tachiyomi.animeextension.fr.frunified;

import android.content.Context;
import androidx.preference.EditTextPreference;

/**
 * Click-only preference compatible with the intentionally minimal Aniyomi v16 AndroidX stubs.
 *
 * <p>The compile-time stub does not declare Preference.onClick(), so this method deliberately has
 * no {@code @Override} annotation. At runtime it overrides the real AndroidX virtual method. The
 * click listener then marks the event handled, preventing PreferenceFragmentCompat from opening
 * EditTextPreference's standard dialog.
 */
public final class ActionPreference extends EditTextPreference {
    private final Runnable action;

    public ActionPreference(Context context, Runnable action) {
        super(context);
        this.action = action;
        setOnPreferenceClickListener(preference -> true);
    }

    @SuppressWarnings("unused")
    protected void onClick() {
        action.run();
    }
}
