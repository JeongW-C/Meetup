from keras.api.datasets import mnist
from keras.api.models import load_model
import matplotlib.pyplot as plt
import numpy as np

_, (x_test, y_test) = mnist.load_data()
x_test = x_test / 255.0

model = load_model('mnist_model.h5')
model.summary()

model.evaluate(x_test, y_test, verbose=2)

n = 10

plt.imshow(x_test[n], cmap='gray')
plt.show()

picks = [n]

# 예측 수행
predictions = model.predict(x_test[picks])

# 각 샘플에 대해 가장 높은 확률을 가진 클래스 선택
predicted_classes = np.argmax(predictions, axis=1)

print("Predict value : ", predicted_classes)