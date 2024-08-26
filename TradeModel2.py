import pyupbit
import numpy as np
import pandas as pd
from datetime import datetime, timedelta
import matplotlib.pyplot as plt
import tensorflow as tf
from keras.api.models import Sequential
from keras.api.layers import Flatten, Dense, LSTM, SimpleRNN
from keras.api.saving import save_model, load_model
from keras.api.callbacks import EarlyStopping
import os
os.environ['TF_ENABLE_ONEDNN_OPTS'] = '0'

n_timesteps = 12
n_features = 4

def read_candles_from_file(filename):
    df = pd.read_csv(filename, index_col=0, parse_dates=True)
    return df

# 벡터 데이터를 시퀀셜하게 n개씩 묶어 X 데이터셋 생성
def create_dataset(vectors, n):
    X = []
    for i in range(len(vectors) - n):
        X.append(vectors[i:i+n])
    return np.array(X)

################## Test

df = read_candles_from_file('xrp_5min_2024-07-27.csv')

# 데이터 정규화
mean_close = np.mean(df['close'].values)
df_normal = df/mean_close

# 데이터 벡터화
vectors = df_normal[['open', 'high', 'low', 'close']].values

model = load_model('trade_model.keras')
model.summary()

test_x = create_dataset(vectors, n_timesteps)
Y = []
for i in range(len(test_x)):
    open_price = test_x[i][0][0]
    if i + n_timesteps < len(vectors):
        close_price = vectors[i + n_timesteps][3]
    else:
        close_price = vectors[-1][3]  # 범위를 벗어나는 경우 마지막 close 값을 사용
    ratio = close_price / open_price
    Y.append(ratio)

calc_y = np.array(Y).reshape(len(test_x))

Y = []
for i in range(len(test_x)):
    net_input = test_x[i]
    net_input = net_input.reshape(1, n_timesteps, n_features)
    predict = model.predict(net_input, verbose=0)
    Y.append(predict)

test_y = np.array(Y).reshape(len(test_x))

model.evaluate(test_x, test_y, verbose=1)

ax_x = range(len(test_x))

plt.plot(ax_x, calc_y, label="truth", color="orange")
plt.plot(ax_x, test_y, label="predict", color="blue")

plt.legend(loc='upper left')
plt.show()