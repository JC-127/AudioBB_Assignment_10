package edu.temple.audiobb

import android.app.DownloadManager
import android.content.*
import android.database.Cursor
import android.graphics.BitmapFactory
import android.icu.text.SimpleDateFormat
import android.net.Uri
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.util.SparseArray
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import edu.temple.audlibplayer.PlayerService
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import android.os.Environment
import org.json.JSONArray
import java.util.*


class MainActivity : AppCompatActivity(), BookListFragment.BookSelectedInterface , ControlFragment.MediaControlInterface{

    //test
    private lateinit var bookListFragment : BookListFragment
    private lateinit var serviceIntent : Intent
    private lateinit var mediaControlBinder : PlayerService.MediaControlBinder
    private var connected = false
    private lateinit var downloadArray : SparseArray<Int>
    private lateinit var preferences:SharedPreferences
    private lateinit var file:File
    private val internalPrefFileName = "my_shared_preferences"
    private var bookList = BookList()
    lateinit var downloadManager :DownloadManager
    lateinit var request : DownloadManager.Request
    lateinit var bookProgress:PlayerService.BookProgress
    private var jsonArray: JSONArray = JSONArray()

    var queueID:Long = 0

    val audiobookHandler = Handler(Looper.getMainLooper()) { msg ->

        msg.obj?.let { msgObj ->
            bookProgress = msgObj as PlayerService.BookProgress

            if (playingBookViewModel.getPlayingBook().value == null) {
                Volley.newRequestQueue(this)
                    .add(JsonObjectRequest(Request.Method.GET, API.getBookDataUrl(bookProgress.bookId), null, { jsonObject ->
                        playingBookViewModel.setPlayingBook(Book(jsonObject))

                        if (selectedBookViewModel.getSelectedBook().value == null) {
                            selectedBookViewModel.setSelectedBook(playingBookViewModel.getPlayingBook().value)

                            bookSelected()
                        }
                    }, {}))
            }


            supportFragmentManager.findFragmentById(R.id.controlFragmentContainerView)?.run{
                with (this as ControlFragment) {
                    playingBookViewModel.getPlayingBook().value?.also {
                        setPlayProgress(((bookProgress.progress / it.duration.toFloat()) * 100).toInt())
                    }
                }
            }
        }

        true
    }

    private val searchRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        supportFragmentManager.popBackStack()
        it.data?.run {
            bookListViewModel.copyBooks(getSerializableExtra(BookList.BOOKLIST_KEY) as BookList)
            bookListFragment.bookListUpdated()
            jsonArray = SearchActivity.getJSONArray()

            with(preferences.edit()) {
                //jsonArray = SearchActivity.getJSONArray()
                Log.d("sharedPref", "Array Retured as " + jsonArray.toString())
                putString("bookList", jsonArray.toString())
                    //Log.d("sharedPref", "On Destroy, List is: " + preferences.getString("bookList", "").toString())
                    .apply()
            }

        }

    }

    private val serviceConnection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mediaControlBinder = service as PlayerService.MediaControlBinder
            mediaControlBinder.setProgressHandler(audiobookHandler)
            connected = true
            if(preferences != null){

                val bookID = preferences.getInt("bookID", 0)
                val bookProgress = preferences.getInt("bookProgress", 0)
                val loadedJson = preferences.getString("bookList", "")
                Log.d("sharedPref", preferences.getString("bookList", "").toString())
                if(loadedJson != ""){
                    var arry = JSONArray(loadedJson)
                    bookListViewModel.populateBooks(arry)
                    bookListFragment.bookListUpdated()
                }

                if(bookID != 0){
                    var book = bookListViewModel.getBookById(bookID)
                    selectedBookViewModel.setSelectedBook(book)
                    bookSelected()

                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connected = false
        }

    }

    private val okClient by lazy{
        OkHttpClient()
    }

    private val okRequest by lazy{
        okhttp3.Request.Builder()
            .url("https://www.istockphoto.com/photo/skyline-of-downtown-philadelphia-at-sunset-gm913241978-251392262")
            .build()
    }

    private val isSingleContainer : Boolean by lazy{
        findViewById<View>(R.id.container2) == null
    }

    private val selectedBookViewModel : SelectedBookViewModel by lazy {
        ViewModelProvider(this).get(SelectedBookViewModel::class.java)
    }

    private val playingBookViewModel : PlayingBookViewModel by lazy {
        ViewModelProvider(this).get(PlayingBookViewModel::class.java)
    }

    private val bookListViewModel : BookList by lazy {
        ViewModelProvider(this).get(BookList::class.java)
    }

    companion object {
        const val BOOKLISTFRAGMENT_KEY = "BookListFragment"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        downloadArray = SparseArray()

        preferences = getPreferences(MODE_PRIVATE)
        file = File(filesDir, internalPrefFileName)
        //autoSave = preferences.getBoolean(AUTO_SAVE_KEY,false)

        var reciever = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                var id = p1?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)

                if(id == queueID){
                    Toast.makeText(applicationContext, "Book Download is Complete", Toast.LENGTH_LONG).show()
                }
            }
        }
        registerReceiver(reciever, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))



        playingBookViewModel.getPlayingBook().observe(this, {
            (supportFragmentManager.findFragmentById(R.id.controlFragmentContainerView) as ControlFragment).setNowPlaying(it.title)
        })

        serviceIntent = Intent(this, PlayerService::class.java)

        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        if (supportFragmentManager.findFragmentById(R.id.container1) is BookDetailsFragment
            && selectedBookViewModel.getSelectedBook().value != null) {
            supportFragmentManager.popBackStack()
        }

        if (savedInstanceState == null) {
            bookListFragment = BookListFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.container1, bookListFragment, BOOKLISTFRAGMENT_KEY)
                .commit()
        } else {
            bookListFragment = supportFragmentManager.findFragmentByTag(BOOKLISTFRAGMENT_KEY) as BookListFragment

            if (isSingleContainer && selectedBookViewModel.getSelectedBook().value != null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container1, BookDetailsFragment())
                    .setReorderingAllowed(true)
                    .addToBackStack(null)
                    .commit()
            }
        }

        if (!isSingleContainer && supportFragmentManager.findFragmentById(R.id.container2) !is BookDetailsFragment)
            supportFragmentManager.beginTransaction()
                .add(R.id.container2, BookDetailsFragment())
                .commit()

        findViewById<ImageButton>(R.id.searchButton).setOnClickListener {
            searchRequest.launch(Intent(this, SearchActivity::class.java))
        }

    }

    override fun onBackPressed() {
        selectedBookViewModel.setSelectedBook(null)

        with(preferences.edit()){
            putInt("bookID", 0)
            putInt("bookProgress", 0)
            commit()
        }
        super.onBackPressed()
    }

    override fun bookSelected() {

        if (isSingleContainer && selectedBookViewModel.getSelectedBook().value != null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container1, BookDetailsFragment())
                .setReorderingAllowed(true)
                .addToBackStack(null)
                .commit()
        }
    }

    override fun play() {
        //downloadAudio(3)
        if (connected && selectedBookViewModel.getSelectedBook().value != null) {
            Log.d("Button pressed", "Play button")
            Log.d("HowPlayed", "Streaming Book")
            mediaControlBinder.play(selectedBookViewModel.getSelectedBook().value!!.id)
            playingBookViewModel.setPlayingBook(selectedBookViewModel.getSelectedBook().value)
            startService(serviceIntent)
        }
    }

    override fun pause() {
        if (connected) mediaControlBinder.pause()
    }

    override fun stop() {
        if (connected) {
            mediaControlBinder.stop()
            stopService(serviceIntent)
        }
    }

    override fun seek(position: Int) {
        if (connected && mediaControlBinder.isPlaying) mediaControlBinder.seekTo((playingBookViewModel.getPlayingBook().value!!.duration * (position.toFloat() / 100)).toInt())
    }

    override fun onDestroy() {
        Log.d("sharedPref", "killing app")
        Log.d("sharedPref", "App Finishing?: " + isFinishing )
        Log.d("sharedPref", "Media playing?: " + mediaControlBinder.isPlaying)


        if(isFinishing && mediaControlBinder.isPlaying) {
            mediaControlBinder.stop()
            with(preferences.edit()) {
                putInt("bookID", bookProgress.bookId)
                putInt("bookProgress", bookProgress.progress)
                commit()
            }

        }

        Log.d("sharedPref", "The Json Array String: " + jsonArray.toString())

        super.onDestroy()
        unbindService(serviceConnection)




    }

    fun checkDownloads(bookID:Int):Int{
        if(downloadArray.contains(bookID)){
            return downloadArray[bookID]
        }
        else{
            return -1
        }
    }

    fun downloadAudio(bookID: Int){

        //var uri = Uri.parse("https://kamorris.com/lab/audlib/download.php?id=3")
        var uri = Uri.parse("https://www.youtube.com/watch?v=c-SDbITS_R4")

        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var request = DownloadManager.Request(uri) as DownloadManager.Request
        request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        request.setTitle("DownloadingBook")
        request.setDescription("Downloading File")
        request.allowScanningByMediaScanner()
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        queueID = downloadManager.enqueue(request)

        Toast.makeText(this, "Download Starting", Toast.LENGTH_LONG).show()

    }

}