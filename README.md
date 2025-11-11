Run Together is a sports analytics and partner-matching platform developed as a final project for a Computer Science degree.  
The system analyzes runners performance, predicts heart rate and running speed using machine learning models, and provides personalized pace recommendations in real time.


Overview:

1. **Android Application (Java)**  
   Collects running data (heart rate, speed, cadence, etc.), displays real-time metrics, and sends them to the backend server.

2. **Backend Server (Node.js + MongoDB)**  
   Manages users, groups, invitations, and run data.  
   The server also communicates with the machine learning module for real-time predictions.

3. **Machine Learning Engine (Python + FastAPI)**  
   Predicts heart rate and recommends optimal running speed based on models trained on real running data.  
   The module exposes an API used by the Node.js server.



## How to Run the Project:

### Step 1 – Run the Machine Learning Engine (Python)

1. Open a terminal and navigate to the ML directory:
   cd ml-engine
   pip install -r requirements.txt

2. Start the FastAPI service:
   python -m uvicorn utils.rt_recommendation_service:app --host 0.0.0.0 --port 8000 --reload

3. The ML service will be available at:
   http://localhost:8000

   To verify, open a browser and go to:
   http://localhost:8000/docs

### Step 2 – Run the Backend Server (Node.js)

1. Open a new terminal and navigate to:
   cd backend-server
   npm install
   npm start

2. The server will automatically connect to MongoDB (based on the .env file).  
   By default, it runs on:
   http://localhost:3000

3. Expected messages:
   MongoDB connected  
   Server running on port 3000

### Step 3 – Run the Android Application

1. Open the folder android-app in Android Studio.  
2. Ensure that the API endpoint in your code is set to:
   http://10.0.2.2:3000
   (10.0.2.2 is how the Android emulator accesses localhost on your computer.)

3. Connect an emulator or physical device and click Run.

The app will send data to the backend, which communicates with the ML engine to return predictions and recommendations.



## System Flow:
Android App → Node.js Server → Python FastAPI → Node.js → Android App

1. The Android app sends running data to /api/simulation on the Node.js server.  
2. The server forwards this data to the ML service at http://localhost:8000/tick.  
3. The ML engine returns predictions and recommendations.  
4. The backend sends them back to the app for display in real time.

## Main Features:
- Heart rate and pace prediction using machine learning  
- Dynamic running recommendations based on physiological data  
- Analysis of early vs. late run performance  
- User registration, history, and team management  
- Runner matching by level, location, and activity type  

## Technologies Used:
Component: Android App  
Technologies: Java, Android Studio

Component: Backend Server  
Technologies: Node.js, Express, MongoDB

Component: Machine Learning  
Technologies: Python, FastAPI, scikit-learn, pandas, numpy