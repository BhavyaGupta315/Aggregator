package com.example.aggregator

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {
    private lateinit var patientManager: PatientManager
    private val patientRepo = PatientRepository()
    private lateinit var patientIdInput: EditText
    private lateinit var registerButton: Button
    private lateinit var loginButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)
        SyncWorkScheduler.schedulePeriodicSync(this)

        patientManager = PatientManager(this)
        patientIdInput = findViewById(R.id.patientIdInput)
        registerButton = findViewById(R.id.registerBtn)
        loginButton = findViewById(R.id.loginBtn)

        registerButton.setOnClickListener { handleRegister() }
        loginButton.setOnClickListener { handleLogin() }
    }

    private fun handleRegister() {
        val name      = findViewById<EditText>(R.id.patientName).text.toString().trim()
        val age       = findViewById<EditText>(R.id.patientAge).text.toString().trim()
        val gender    = findViewById<EditText>(R.id.patientGender).text.toString().trim()
        val bloodType = findViewById<EditText>(R.id.patientBloodType).text.toString().trim()
        val pin       = findViewById<EditText>(R.id.pinInput).text.toString().trim()

        if (name.isEmpty() || age.isEmpty() || gender.isEmpty() || bloodType.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }
        if (pin.length < 4) {
            Toast.makeText(this, "Set a PIN of at least 4 digits", Toast.LENGTH_SHORT).show()
            return
        }

        val patient = Patient(
            name      = name,
            age       = age.toIntOrNull() ?: 0,
            gender    = gender,
            bloodType = bloodType
        )

        // Provision the PIN-derived DB key BEFORE any DB access (the DB is encrypted).
        AggregatorSession.provision(this, pin, patient.id)

        // Save locally immediately so the app works even if backend is offline
        patientManager.savePatient(patient)
        SyncWorkScheduler.schedulePeriodicSync(this)
        SyncWorkScheduler.enqueueImmediateSync(this)
        patientIdInput.setText(patient.id)

        // Register with backend in background — non-blocking
        lifecycleScope.launch {
            setLoading(true)
            patientRepo.register(patient).fold(
                onSuccess = { reg ->
                    val creds = reg.credentials
                    val credNote = if (creds?.privateKey != null &&
                        CredentialStore.saveFromServer(this@AuthActivity, patient.id, creds)
                    ) "credentials secured" else "no credentials"
                    Toast.makeText(
                        this@AuthActivity,
                        "Registered: $name (Patient ID: ${patient.id}, $credNote)",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = {
                    Toast.makeText(
                        this@AuthActivity,
                        "Saved locally (Patient ID: ${patient.id}, ${it.message})",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
            setLoading(false)
        }

        goToMain(patient.name)
    }

    private fun handleLogin() {
        val patientId = patientIdInput.text.toString().trim()
        val pin       = findViewById<EditText>(R.id.pinInput).text.toString().trim()
        if (patientId.isEmpty()) {
            Toast.makeText(this, "Enter your Patient ID", Toast.LENGTH_SHORT).show()
            return
        }
        if (pin.isEmpty()) {
            Toast.makeText(this, "Enter your PIN", Toast.LENGTH_SHORT).show()
            return
        }

        // Provision the PIN-derived key and verify it opens the encrypted DB.
        AggregatorSession.provision(this, pin, patientId)
        val openError: String? = try {
            patientManager.getCurrentPatient()       // opens the encrypted DB (fails here on wrong PIN)
            CredentialStore.loadIntoSession(this)     // load credential into memory
            null
        } catch (e: Exception) {
            AggregatorSession.lock()
            e.message.orEmpty()
        }
        if (openError != null) {
            val reason = if (openError.contains("not a database", true) ||
                openError.contains("encrypted", true) || openError.contains("file is not", true)) {
                "incorrect PIN (could not decrypt your data)"
            } else {
                "could not open your encrypted data: $openError"
            }
            Toast.makeText(this, "Login failed: $reason", Toast.LENGTH_LONG).show()
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            patientRepo.login(patientId).fold(
                onSuccess = { cloudPatient ->
                    val localPatient = Patient(
                        id = cloudPatient.patientId,
                        name = cloudPatient.name,
                        age = cloudPatient.age ?: 0,
                        gender = cloudPatient.gender.orEmpty(),
                        bloodType = cloudPatient.bloodType.orEmpty()
                    )
                    patientManager.savePatient(localPatient)

                    val syncMessage = patientRepo.syncPatientRecords(this@AuthActivity, cloudPatient).fold(
                        onSuccess = { count -> "Fetched $count record(s) from cloud." },
                        onFailure = { error -> "Logged in, but record download failed: ${error.message}" }
                    )

                    Toast.makeText(
                        this@AuthActivity,
                        "Welcome back, ${cloudPatient.name}! $syncMessage",
                        Toast.LENGTH_LONG
                    ).show()
                    SyncWorkScheduler.schedulePeriodicSync(this@AuthActivity)
                    SyncWorkScheduler.enqueueImmediateSync(this@AuthActivity)
                    goToMain(cloudPatient.name)
                },
                onFailure = { error ->
                    val cachedPatient = patientManager.getCurrentPatient()
                    if (cachedPatient?.id == patientId) {
                        Toast.makeText(
                            this@AuthActivity,
                            "Using cached patient data (${error.message})",
                            Toast.LENGTH_LONG
                        ).show()
                        goToMain(cachedPatient.name)
                    } else {
                        Toast.makeText(
                            this@AuthActivity,
                            error.message ?: "Login failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
            setLoading(false)
        }
    }

    private fun goToMain(patientName: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("patient_name", patientName)
        })
        finish()
    }

    private fun setLoading(loading: Boolean) {
        registerButton.isEnabled = !loading
        loginButton.isEnabled = !loading
    }
}
