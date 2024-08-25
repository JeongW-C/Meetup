import pyupbit
import numpy as np
import pandas as pd
from datetime import datetime, timedelta
import matplotlib.pyplot as plt
import tensorflow as tf
from keras.api.models import Sequential
from keras.api.layers import Flatten, Dense, LSTM, SimpleRNN
from keras.api.saving import save_model
from keras.api.callbacks import EarlyStopping
import os
os.environ['TF_ENABLE_ONEDNN_OPTS'] = '0'

n_timesteps = 12
n_features = 5

# 특정일 지정
specific_date = "2024-08-10"
start_time = datetime.strptime(specific_date, "%Y-%m-%d")
end_time = start_time + timedelta(days=7)

# 5분봉 데이터 추출
df = pyupbit.get_ohlcv("KRW-XRP", interval="minute5", to=end_time.strftime("%Y-%m-%d %H:%M:%S"))
df = df[df.index >= start_time]

# 데이터 벡터화
vectors = df[['open', 'high', 'low', 'close', 'volume']].values

# 벡터 데이터를 시퀀셜하게 n개씩 묶어 X 데이터셋 생성
def create_dataset(vectors, n):
    X = []
    for i in range(len(vectors) - n):
        X.append(vectors[i:i+n])
    return np.array(X)

X = create_dataset(vectors, n_timesteps)

# 각 데이터셋의 결과값 Y 리스트 생성
Y = []
for i in range(len(X)):
    open_price = X[i][0][0]
    if i + n_timesteps < len(vectors):
        close_price = vectors[i + n_timesteps][3]
    else:
        close_price = vectors[-1][3]  # 범위를 벗어나는 경우 마지막 close 값을 사용
    ratio = close_price / open_price
    Y.append(ratio)

Y = np.array(Y)

# 데이터셋 확인
print(f"X shape: {X.shape}")
print(f"Y shape: {Y.shape}")

model = Sequential()
model.add(LSTM(units=10,
               return_sequences=False,
               input_shape=(n_timesteps, n_features)))
model.add(Dense(1))
model.compile(optimizer='adam', loss='mse')
np.random.seed(0)

early_stopping = EarlyStopping(
    monitor='loss',
    patience=5,
    mode='auto'
)

history = model.fit(X, Y, epochs=1000, callbacks=[early_stopping])

plt.plot(history.history['loss'], label='loss')
plt.legend(loc='upper right')
plt.show()

#model.save('lstm_model.keras')




################## Test

# 특정일 지정
specific_date = "2024-08-20"
start_time = datetime.strptime(specific_date, "%Y-%m-%d")
end_time = start_time + timedelta(days=7)

# 5분봉 데이터 추출
df = pyupbit.get_ohlcv("KRW-XRP", interval="minute5", to=end_time.strftime("%Y-%m-%d %H:%M:%S"))
df = df[df.index >= start_time]

# 데이터 벡터화
vectors = df[['open', 'high', 'low', 'close', 'volume']].values

test_x = create_dataset(vectors, n_timesteps)
Y = []
for i in range(len(X)):
    open_price = X[i][0][0]
    if i + n_timesteps < len(vectors):
        close_price = vectors[i + n_timesteps][3]
    else:
        close_price = vectors[-1][3]  # 범위를 벗어나는 경우 마지막 close 값을 사용
    ratio = close_price / open_price
    Y.append(ratio)

calc_y = np.array(Y)

Y = []
for i in range(len(test_x)):
    net_input = test_x[i]
    net_input = net_input.reshape(1, n_timesteps, n_features)
    predict = model.predict(net_input, verbose=0)
    print(net_input, predict)
    Y.append(predict)

test_y = np.array(Y)

print(test_y)
print(test_y.shape)

plt.plot(test_x, calc_y, label="truth", color="orange")
plt.plot(test_x, test_y, label="predict", color="blue")

plt.legend(loc='upper left')
plt.show()