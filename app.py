

from flask import Flask, render_template, request, jsonify
from predict import predict_flood
import pandas as pd

app = Flask(__name__)

@app.route('/')
def home():
    return render_template('index.html')

@app.route('/floodPrediction.html')
def flood_page():
    return render_template('floodPrediction.html')

@app.route('/index2.html')
def case_study():
    return render_template('index2.html')

@app.route('/predict', methods=['POST'])
def predict():
    data = request.get_json()
    village = data.get('village')
    start_date = data.get('start_date')
    end_date = data.get('end_date')

    try:
        if start_date:
            start_date = pd.to_datetime(start_date).date()
        if end_date:
            end_date = pd.to_datetime(end_date).date()

        result = predict_flood(village, start_date=start_date, end_date=end_date)
        return jsonify(result)

    except Exception as e:
        import traceback
        print("🔥 SERVER ERROR:", e)
        traceback.print_exc()
        return jsonify({"error": str(e)}), 500


if __name__ == '__main__':
    app.run(debug=True)
