/*
 * Copyright (C) 2007 The Android Open Source Project
 * Copyright (C) 2012-2014 Mikael Ståldal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.staldal.djdplayer;

import android.app.Activity;
import android.content.*;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

public class MediaPlaybackActivity extends Activity implements MusicUtils.Defs, ServiceConnection {

    private static final String LOGTAG = "MediaPlaybackActivity";

    private MusicUtils.ServiceToken token = null;
    private MediaPlaybackService service = null;

    private PlayerHeaderFragment playerHeaderFragment;
    private PlayQueueFragment playQueueFragment;
    private PlayerFooterFragment playerFooterFragment;
    private View playQueueDivider;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(LOGTAG, "onCreate - " + getIntent());

        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        getActionBar().setHomeButtonEnabled(true);

        setContentView(R.layout.audio_player);

        playerHeaderFragment = (PlayerHeaderFragment)getFragmentManager().findFragmentById(R.id.player_header);
        playQueueFragment = (PlayQueueFragment)getFragmentManager().findFragmentById(R.id.playqueue);
        playerFooterFragment = (PlayerFooterFragment)getFragmentManager().findFragmentById(R.id.player_footer);
        playQueueDivider = findViewById(R.id.playqueue_divider);

        token = MusicUtils.bindToService(this, this);
    }

    @Override
    public void onNewIntent(Intent intent) {
        Log.i(LOGTAG, "onNewIntent - " + getIntent());
        setIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter f = new IntentFilter();
        f.addAction(MediaPlaybackService.META_CHANGED);
        f.addAction(MediaPlaybackService.QUEUE_CHANGED);
        registerReceiver(mStatusListener, new IntentFilter(f));
    }

    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((MediaPlaybackService.MediaPlaybackServiceBinder)binder).getService();

        playerHeaderFragment.onServiceConnected(service);
        playQueueFragment.onServiceConnected(service);
        playerFooterFragment.onServiceConnected(service);

        invalidateOptionsMenu();
        startPlayback();

        // Assume something is playing when the service says it is,
        // but also if the audio ID is valid but the service is paused.
        if (this.service.getAudioId() >= 0 || this.service.isPlaying() ||
                this.service.getPath() != null) {
            // something is playing now, we're done
            return;
        }
        // Service is dead or not playing anything. If we got here as part
        // of a "play this file" Intent, exit. Otherwise go to the Music
        // app start screen.
        if (getIntent().getData() == null) {
            Intent intent = new Intent(MediaPlaybackActivity.this, MusicBrowserActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        finish();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateTrackInfo();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.player_menu, menu);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateSoundEffectItem(menu);

        updateRepeatItem(menu);

        updatePlayingItems(menu);

        return true;
    }

    private void updateSoundEffectItem(Menu menu) {
        Intent i = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
        MenuItem item = menu.findItem(R.id.effect_panel);
        item.setVisible(getPackageManager().resolveActivity(i, 0) != null);
    }

    private void updateRepeatItem(Menu menu) {
        MenuItem item = menu.findItem(R.id.repeat);

        if (service != null) {
            switch (service.getRepeatMode()) {
                case MediaPlaybackService.REPEAT_ALL:
                    item.setIcon(R.drawable.ic_mp_repeat_all_btn);
                    break;
                case MediaPlaybackService.REPEAT_CURRENT:
                    item.setIcon(R.drawable.ic_mp_repeat_once_btn);
                    break;
                case MediaPlaybackService.REPEAT_STOPAFTER:
                    item.setIcon(R.drawable.ic_mp_repeat_stopafter_btn);
                    break;
                default:
                    item.setIcon(R.drawable.ic_mp_repeat_off_btn);
                    break;
            }
        } else {
            item.setIcon(R.drawable.ic_mp_repeat_off_btn);
        }
    }

    private void updatePlayingItems(Menu menu) {
        menu.setGroupVisible(R.id.playing_items, service != null && !service.isPlaying());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                Intent intent = new Intent(this, MusicBrowserActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
                return true;
            }

            case R.id.zoom_queue:
                if (playQueueFragment.isQueueZoomed()) {
                    playerHeaderFragment.show();
                    playerFooterFragment.show();
                    if (playQueueDivider != null) playQueueDivider.setVisibility(View.VISIBLE);
                    playQueueFragment.setQueueZoomed(false);
                } else {
                    playerHeaderFragment.hide();
                    playerFooterFragment.hide();
                    if (playQueueDivider != null) playQueueDivider.setVisibility(View.GONE);
                    playQueueFragment.setQueueZoomed(true);
                }
                return true;

            case R.id.repeat:
                cycleRepeat();
                return true;

            case R.id.shuffle:
                if (service != null) service.doShuffle();
                return true;

            case R.id.uniqueify:
                if (service != null) service.uniqueify();
                return true;

            case R.id.clear_queue:
                if (service != null) service.removeTracks(0, Integer.MAX_VALUE);
                return true;

            case R.id.settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;

            case R.id.search:
                return onSearchRequested();

            case R.id.effect_panel: {
                Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
                intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, service.getAudioSessionId());
                startActivityForResult(intent, 0);
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void cycleRepeat() {
        if (service == null) {
            return;
        }
        int mode = service.getRepeatMode();
        if (mode == MediaPlaybackService.REPEAT_NONE) {
            service.setRepeatMode(MediaPlaybackService.REPEAT_ALL);
            Toast.makeText(this, R.string.repeat_all_notif, Toast.LENGTH_SHORT).show();
        } else if (mode == MediaPlaybackService.REPEAT_ALL) {
            service.setRepeatMode(MediaPlaybackService.REPEAT_CURRENT);
            Toast.makeText(this, R.string.repeat_current_notif, Toast.LENGTH_SHORT).show();
        } else if (mode == MediaPlaybackService.REPEAT_CURRENT) {
            service.setRepeatMode(MediaPlaybackService.REPEAT_STOPAFTER);
            Toast.makeText(this, R.string.repeat_stopafter_notif, Toast.LENGTH_SHORT).show();
        } else {
            service.setRepeatMode(MediaPlaybackService.REPEAT_NONE);
            Toast.makeText(this, R.string.repeat_off_notif, Toast.LENGTH_SHORT).show();
        }
        invalidateOptionsMenu();
    }

    private void startPlayback() {
        if (service == null)
            return;
        Intent intent = getIntent();
        Uri uri = intent.getData();
        if (uri != null && uri.toString().length() > 0) {
            // If this is a file:// URI, just use the path directly instead
            // of going through the open-from-filedescriptor codepath.
            String filename;
            if ("file".equals(uri.getScheme())) {
                filename = uri.getPath();
            } else {
                filename = uri.toString();
            }
            try {
                service.stop();
                service.open(filename);
                service.play();
                setIntent(new Intent());
            } catch (Exception ex) {
                Log.d(LOGTAG, "couldn't start playback: " + ex);
            }
        }

        updateTrackInfo();
    }

    public void onServiceDisconnected(ComponentName name) {
        service = null;

        playerHeaderFragment.onServiceDisconnected();
        playQueueFragment.onServiceDisconnected();
        playerFooterFragment.onServiceDisconnected();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(mStatusListener);

        super.onStop();
    }

    @Override
    public void onDestroy() {
        if (token != null) MusicUtils.unbindFromService(token);
        service = null;

        super.onDestroy();
    }

    private final BroadcastReceiver mStatusListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateTrackInfo();
        }
    };

    private void updateTrackInfo() {
        if (service == null) return;

        String path = service.getPath();
        if (path == null) {
            finish();
            return;
        }

        setTitle((service.getQueuePosition() + 1) + "/" + service.getQueueLength());
    }
}
