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
n_features = 4

def read_candles_from_file(filename):
    df = pd.read_csv(filename, index_col=0, parse_dates=True)
    return df

df = read_candles_from_file('xrp_5min_2024-07-28.csv')

# 데이터 정규화
mean_close = np.mean(df['close'].values)
df_normal = df/mean_close

# 데이터 벡터화
vectors = df_normal[['open', 'high', 'low', 'close']].values

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
model.add(LSTM(units=13,
               activation='tanh',
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

model.save('trade_model.keras')