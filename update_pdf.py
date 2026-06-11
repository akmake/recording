import os

content_vm = '''package com.example.goodstart.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

data class PdfBook(
    val id: String,
    val uriString: String,
    val title: String,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val isLandscape: Boolean = false
)

data class PdfState(
    val books: List<PdfBook> = emptyList(),
    val currentBook: PdfBook? = null,
    val currentBitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val errorMsg: String? = null
)

class PdfStudyViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val prefs = ctx.getSharedPreferences("PdfLibrary", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _state = MutableStateFlow(PdfState())
    val state = _state.asStateFlow()

    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentPdfPage: PdfRenderer.Page? = null

    init {
        loadBooks()
    }

    private fun loadBooks() {
        val json = prefs.getString("books_list", "[]")
        val type = object : TypeToken<List<PdfBook>>() {}.type
        val books: List<PdfBook> = try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
        _state.value = _state.value.copy(books = books)
    }

    private fun saveBooks(books: List<PdfBook>) {
        val json = gson.toJson(books)
        prefs.edit().putString("books_list", json).apply()
        _state.value = _state.value.copy(books = books)
    }

    fun addBook(uri: Uri, title: String, isLandscape: Boolean) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            Log.e("PdfStudyVM", "Could not take persistable perm", e)
        }
        val newBook = PdfBook(
            id = UUID.randomUUID().toString(),
            uriString = uri.toString(),
            title = title,
            isLandscape = isLandscape
        )
        val updatedList = _state.value.books + newBook
        saveBooks(updatedList)
    }

    fun removeBook(bookId: String) {
        val updatedList = _state.value.books.filter { it.id != bookId }
        saveBooks(updatedList)
    }

    fun openBook(bookId: String) {
        val book = _state.value.books.find { it.id == bookId } ?: return
        _state.value = _state.value.copy(currentBook = book, errorMsg = null)
        closePdf()
        loadPdf(Uri.parse(book.uriString), book.currentPage)
    }

    fun closeBook() {
        closePdf()
        _state.value = _state.value.copy(currentBook = null, currentBitmap = null)
        loadBooks()
    }

    private fun loadPdf(uri: Uri, startPage: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoading = true)
            try {
                fileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r")
                if (fileDescriptor == null) throw IOException("Cannot open file")

                pdfRenderer = PdfRenderer(fileDescriptor!!)
                val total = pdfRenderer!!.pageCount
                val pageToLoad = if (startPage in 0 until total) startPage else 0
                
                val activeBook = _state.value.currentBook!!
                val updatedBook = activeBook.copy(totalPages = total)
                
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(currentBook = updatedBook)
                    updateBookInList(updatedBook)
                }

                renderPage(pageToLoad)

            } catch (e: Exception) {
                Log.e("PdfStudyVM", "Error loading PDF", e)
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMsg = "שגיאה פתח קובץ שוב",
                        currentBook = null
                    )
                }
            }
        }
    }

    private fun updateBookInList(book: PdfBook) {
        val currentBooks = _state.value.books.toMutableList()
        val index = currentBooks.indexOfFirst { it.id == book.id }
        if (index != -1) {
            currentBooks[index] = book
            saveBooks(currentBooks)
        }
    }

    fun nextPage() {
        val book = _state.value.currentBook ?: return
        if (book.currentPage < book.totalPages - 1) {
            renderPage(book.currentPage + 1)
        }
    }

    fun prevPage() {
        val book = _state.value.currentBook ?: return
        if (book.currentPage > 0) {
            renderPage(book.currentPage - 1)
        }
    }

    private fun renderPage(pageIndex: Int) {
        val activeBook = _state.value.currentBook ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoading = true)
            try {
                currentPdfPage?.close()
                currentPdfPage = pdfRenderer?.openPage(pageIndex)

                val page = currentPdfPage
                if (page != null) {
                    val width = (ctx.resources.displayMetrics.widthPixels * 2.5).toInt()
                    val height = (width * page.height / page.width)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val updatedBook = activeBook.copy(currentPage = pageIndex)

                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(
                            currentBook = updatedBook,
                            currentBitmap = bitmap,
                            isLoading = false
                        )
                        updateBookInList(updatedBook)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        closePdf()
    }

    private fun closePdf() {
        currentPdfPage?.close()
        pdfRenderer?.close()
        fileDescriptor?.close()
        currentPdfPage = null
        pdfRenderer = null
        fileDescriptor = null
    }
}
'''

with open('app/src/main/java/com/example/goodstart/ui/viewmodel/PdfStudyViewModel.kt', 'w', encoding='utf-8') as f:
    f.write(content_vm)
