package utils;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Usage **

public class MainActivity extends Activity {
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        this.sessionManager = SessionManager.getInstance(this, new SessionManager.SessionCompleteCallback() {
            @Override
            public void onSessionComplete(SessionManager.Session session) {
                Log.d("MY APP", "session " + session.getUuid() + " is now closed");

            }
        });
        this.sessionManager.startSession();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        this.sessionManager.startSession();

    }

    @Override
    public void onPause()
    {
        this.sessionManager.endSession();
        super.onPause();
    }
}

 */

public class SessionManager {

    private SessionManager(Context context, SessionCompleteCallback callback) {
        this.context = context.getApplicationContext();
        this.sessionCompleteCallback = callback; // this will be called any time a session is complete
        HandlerThread handlerThread = new HandlerThread(getClass().getCanonicalName());
        handlerThread.start();
        this.handler = new SessionHandler(this, handlerThread.getLooper());
        this.handler.sendEmptyMessage(MESSAGE_INIT);
    }

    public static SessionManager getInstance(Context context, SessionCompleteCallback callback) {
        if (instance == null) {
            instance = new SessionManager(context, callback);
        }
        return instance;
    }

    public void startSession() {
        handler.sendEmptyMessage(MESSAGE_START_SESSION);
    }

    public void endSession() {
        handler.sendEmptyMessage(MESSAGE_END_SESSION);
    }

    private void _startSession() {
        if (curSession == null) {
            if (prevSession != null && !prevSession.isExpired()) {
               Log.d(LOGTAG, "resuming session " + prevSession.getUuid());
               curSession = prevSession;
               curSession.resume();
               prevSession = null;
            } else {
                curSession = new Session();
                Log.d(LOGTAG, "creating new session " + curSession.getUuid());
                synchronized (sessionsLock) {
                    sessions.add(curSession);
                    _writeSessionsToFile();
                    this._initSessionCompleter();
                }
            }
        }
    }

    private void _endSession() {
        if (curSession != null) {
            curSession.end();
            prevSession = curSession;
            curSession = null;
        }
    }

    private void _initSessionCompleter() {
        if (sessionCompleterThread == null || !sessionCompleterThread.isAlive()) {
            sessionCompleterThread = new Thread() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            _completeExpiredSessions();
                            sleep(1000);
                        }
                    } catch (InterruptedException e) {
                        Log.e(LOGTAG, "expiration watcher thread interrupted", e);
                    }
                }

                private void _completeExpiredSessions() {
                    Log.d(LOGTAG, "checking for expired sessions...");
                    synchronized(sessionsLock) {
                        Iterator<Session> iterator = sessions.iterator();
                        while (iterator.hasNext()) {
                            Session session = iterator.next();
                            if (session.isExpired()) {
                                Log.d(LOGTAG, "expiring session id " + session.getUuid());
                                iterator.remove();
                                _writeSessionsToFile();
                                sessionCompleteCallback.onSessionComplete(session);
                            } else {
                                Log.d(LOGTAG, "session id " + session.getUuid() + " not yet expired...");
                            }
                        }

                    }
                }
            };
            sessionCompleterThread.start();
        }
    }

    private void _loadSessionsFromFile() {
        FileInputStream fis = null;
        try {
            fis = context.openFileInput(SESSIONS_FILE_NAME);
            InputStreamReader isr = new InputStreamReader(fis);
            BufferedReader bufferedReader = new BufferedReader(isr);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                sb.append(line);
            }
            JSONArray sessionsJson = new JSONArray(sb.toString());

            synchronized(sessionsLock) {
                for (int i = 0; i < sessionsJson.length(); i++) {
                    JSONObject sessionsObj = sessionsJson.getJSONObject(i);
                    Session session = new Session(sessionsObj);
                    if (session.getEndTime() == null) {
                        session.end();
                    }
                    sessions.add(session);
                }
                if (sessions.size() > 0) {
                    this._initSessionCompleter();
                }
            }
        } catch (FileNotFoundException e) {
            Log.e(LOGTAG, "Could not find sessions file", e);
        } catch (IOException e) {
            Log.e(LOGTAG, "Could not read from sessions file", e);
        } catch (JSONException e) {
            Log.e(LOGTAG, "Could not serialize json string from file", e);
        }
    }

    private void _writeSessionsToFile() {
        FileOutputStream fos = null;
        try {
            fos = context.openFileOutput(SESSIONS_FILE_NAME, Context.MODE_PRIVATE);
            JSONArray jsonArray = new JSONArray();
            for (Session session : sessions) {
                jsonArray.put(session.toJSON());
            }
            fos.write(jsonArray.toString().getBytes());
            fos.close();
        } catch (FileNotFoundException e) {
            Log.e(LOGTAG, "Could not find sessions file", e);
        } catch (IOException e) {
            Log.e(LOGTAG, "Could not write to sessions file", e);
        } catch (JSONException e) {
            Log.e(LOGTAG, "Could not turn session to JSON", e);
        }
    }

    public class Session {
        private String uuid = null;
        private Long startTime = null;
        private Long endTime = null;
        private Long sessionExpirationGracePeriod = 15000L;

        public Session() {
            this.uuid = UUID.randomUUID().toString();
            this.startTime = System.currentTimeMillis();
        }

        public Session(JSONObject jsonObject) throws JSONException {
            this.uuid = jsonObject.getString("uuid");
            this.startTime = jsonObject.getLong("startTime");
            if (jsonObject.has("endTime")) {
                this.endTime = jsonObject.getLong("endTime");
            }
            this.sessionExpirationGracePeriod = jsonObject.getLong("sessionExpirationGracePeriod");
        }

        public JSONObject toJSON() throws JSONException {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("uuid", this.uuid);
            jsonObject.put("startTime", this.startTime);
            jsonObject.put("endTime", this.endTime);
            jsonObject.put("sessionExpirationGracePeriod", this.sessionExpirationGracePeriod);
            return jsonObject;
        }

        public void resume() {
            this.endTime = null;
        }

        public void end() {
            this.endTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return this.getEndTime() != null && System.currentTimeMillis() > this.getEndTime() + this.getsessionExpirationGracePeriod();
        }

        public String getUuid() {
            return uuid;
        }

        public Long getStartTime() {
            return startTime;
        }

        public Long getEndTime() {
            return endTime;
        }

        public Long getsessionExpirationGracePeriod() {
            return sessionExpirationGracePeriod;
        }
    }

    public interface SessionCompleteCallback {
        public void onSessionComplete(Session session);
    }

    public class SessionHandler extends Handler {
        private SessionManager sessionManager;

        public SessionHandler(SessionManager sessionManager, Looper looper) {
            super(looper);
            this.sessionManager = sessionManager;
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MESSAGE_INIT:
                    sessionManager._loadSessionsFromFile();
                    break;
                case MESSAGE_START_SESSION:
                    sessionManager._startSession();
                    break;
                case MESSAGE_END_SESSION:
                    sessionManager._endSession();
                    break;
            }
        }
    }

    private static String LOGTAG = "SessionManager";
    private static String SESSIONS_FILE_NAME = "user_sessions";
    private static final int MESSAGE_INIT = 0;
    private static final int MESSAGE_START_SESSION = 1;
    private static final int MESSAGE_END_SESSION = 2;

    private static SessionManager instance = null;
    private static final Object[] sessionsLock = new Object[0];
    private List<Session> sessions = new ArrayList<Session>();
    private Session curSession = null;
    private Session prevSession = null;
    private SessionHandler handler;
    private Context context = null;
    private Thread sessionCompleterThread = null;
    private final SessionCompleteCallback sessionCompleteCallback;
}

